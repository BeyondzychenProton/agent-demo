package com.beyondzy.demo.mcp;

import io.agentscope.core.tool.Toolkit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 把一家 MCP 的 tools/list 注册进 Toolkit。多家服务由装配方逐个调用。注册名把点号换成下划线，供 OpenAI 兼容接口使用。 */
public final class McpToolMount {

    private static final Logger logger = LoggerFactory.getLogger(McpToolMount.class);
    private static final int MAX_LENGTH = 64;
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private McpToolMount() {}

    public static List<McpBoundTool> install(Toolkit toolkit, McpServer server) {
        if (toolkit == null) throw new IllegalArgumentException("toolkit 不能为空");
        if (server == null) throw new IllegalArgumentException("MCP 服务不能为空");
        List<McpBoundTool> tools = install(toolkit, server.id(), server.toolsForMount(), server);
        server.mounted(tools);
        return tools;
    }

    private static List<McpBoundTool> install(
            Toolkit toolkit,
            String serverId,
            List<McpToolDefinition> definitions,
            McpServer server) {
        List<McpToolDefinition> usable = new ArrayList<>();
        for (McpToolDefinition definition : definitions == null ? List.<McpToolDefinition>of() : definitions) {
            if (definition == null || definition.name() == null || definition.name().isBlank()) {
                logger.warn("跳过缺少名称的 MCP 工具");
                continue;
            }
            Map<String, Object> schema = definition.inputSchema();
            if (schema == null || schema.isEmpty()) {
                logger.warn("MCP 工具 {} 没有 inputSchema，按开放对象注册", definition.name());
                schema = openSchema();
            }
            usable.add(new McpToolDefinition(definition.name(), definition.description(), schema, definition.write()));
        }
        List<String> names = assign(serverId, usable);
        List<McpBoundTool> mounted = new ArrayList<>();
        for (int i = 0; i < usable.size(); i++) {
            String registered = avoidCollision(toolkit, names.get(i), serverId, usable.get(i).name());
            McpBoundTool tool = new McpBoundTool(server, registered, usable.get(i));
            toolkit.registerAgentTool(tool);
            mounted.add(tool);
        }
        return List.copyOf(mounted);
    }

    private static String avoidCollision(Toolkit toolkit, String registered, String serverId, String sourceName) {
        if (toolkit.getTool(registered) == null) return registered;
        String suffix = "_mcp";
        String stem = registered;
        if (stem.length() + suffix.length() > MAX_LENGTH) {
            stem = stem.substring(0, MAX_LENGTH - suffix.length());
        }
        String candidate = stem + suffix;
        if (toolkit.getTool(candidate) != null) {
            candidate = stem + "_" + digest8(toolUri(serverId, sourceName));
            if (candidate.length() > MAX_LENGTH) {
                candidate = candidate.substring(0, MAX_LENGTH);
            }
        }
        logger.warn("MCP 工具 {} 的注册名 {} 已被占用，改用 {}", sourceName, registered, candidate);
        return candidate;
    }

    private static String toolUri(String serverId, String sourceName) {
        String id = serverId == null || serverId.isBlank() ? "mcp" : serverId.trim();
        return "mcp://" + id + "/" + sourceName;
    }

    private static List<String> assign(String serverId, List<McpToolDefinition> tools) {
        List<String> bases = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        for (McpToolDefinition tool : tools) {
            String base = sanitize(tool.name());
            bases.add(base);
            counts.merge(base, 1, Integer::sum);
        }
        Set<String> used = new HashSet<>();
        List<String> assigned = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            String base = bases.get(i);
            boolean plain = counts.get(base) == 1
                    && SAFE.matcher(base).matches()
                    && !used.contains(base);
            String registered = plain ? base : disambiguate(base, toolUri(serverId, tools.get(i).name()), used);
            used.add(registered);
            assigned.add(registered);
        }
        return assigned;
    }

    private static String digest8(String uri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((uri == null ? "" : uri).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "tool";
        StringBuilder value = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint <= 0x7F) {
                char c = (char) codePoint;
                if (isSafe(c)) value.append(c);
                else value.append('_');
            } else {
                value.append('_');
            }
        }
        String sanitized = value.toString();
        if (sanitized.isBlank() || sanitized.chars().allMatch(ch -> ch == '_')) return "tool";
        return sanitized;
    }

    private static String disambiguate(String base, String uri, Set<String> used) {
        String suffix = "_" + digest8(uri);
        String stem = base == null || base.isBlank() ? "tool" : base;
        if (stem.length() + suffix.length() > MAX_LENGTH) {
            stem = stem.substring(0, MAX_LENGTH - suffix.length());
        }
        String candidate = stem + suffix;
        int serial = 2;
        while (used.contains(candidate) || !SAFE.matcher(candidate).matches()) {
            String tail = "_" + serial++;
            String head = stem + suffix;
            if (head.length() + tail.length() > MAX_LENGTH) head = head.substring(0, MAX_LENGTH - tail.length());
            candidate = head + tail;
            if (serial > 1000) throw new IllegalStateException("无法为 MCP 工具分配名称");
        }
        return candidate;
    }

    private static boolean isSafe(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-';
    }

    private static Map<String, Object> openSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return schema;
    }
}
