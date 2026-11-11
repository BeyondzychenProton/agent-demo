package com.beyondzy.demo.mcp;

import com.beyondzy.demo.governance.GovernanceToolNames;
import com.beyondzy.demo.governance.GovernanceToolView;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 启动时把 tools/list 的结果注册进 Toolkit。注册名把点号换成下划线，供 OpenAI 兼容接口使用。 */
public final class McpToolMount {

    private static final Logger logger = LoggerFactory.getLogger(McpToolMount.class);

    private McpToolMount() {}

    public static List<McpBoundTool> install(
            Toolkit toolkit,
            List<McpToolDefinition> definitions,
            McpToolCaller caller,
            McpPhoneLookup phones) {
        if (toolkit == null) throw new IllegalArgumentException("toolkit 不能为空");
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
        List<GovernanceToolView> views = new ArrayList<>();
        for (McpToolDefinition definition : usable) {
            views.add(new GovernanceToolView(
                    "mcp://cms/" + definition.name(),
                    definition.name(),
                    definition.description(),
                    definition.inputSchema(),
                    null,
                    null,
                    null,
                    definition.write() ? "write" : "read"));
        }
        List<GovernanceToolNames.Assignment> names = GovernanceToolNames.assign(views);
        List<McpBoundTool> mounted = new ArrayList<>();
        for (int i = 0; i < usable.size(); i++) {
            String registered = avoidCollision(toolkit, names.get(i).registeredName(), usable.get(i).name());
            McpBoundTool tool = new McpBoundTool(caller, phones, registered, usable.get(i));
            toolkit.registerAgentTool(tool);
            mounted.add(tool);
        }
        return List.copyOf(mounted);
    }

    static String avoidCollision(Toolkit toolkit, String registered, String sourceName) {
        if (toolkit.getTool(registered) == null) return registered;
        String suffix = "_mcp";
        String stem = registered;
        if (stem.length() + suffix.length() > GovernanceToolNames.MAX_LENGTH) {
            stem = stem.substring(0, GovernanceToolNames.MAX_LENGTH - suffix.length());
        }
        String candidate = stem + suffix;
        if (toolkit.getTool(candidate) != null) {
            candidate = stem + "_" + GovernanceToolNames.digest8("mcp://cms/" + sourceName);
            if (candidate.length() > GovernanceToolNames.MAX_LENGTH) {
                candidate = candidate.substring(0, GovernanceToolNames.MAX_LENGTH);
            }
        }
        logger.warn("MCP 工具 {} 的注册名 {} 已被占用，改用 {}", sourceName, registered, candidate);
        return candidate;
    }

    private static Map<String, Object> openSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return schema;
    }
}
