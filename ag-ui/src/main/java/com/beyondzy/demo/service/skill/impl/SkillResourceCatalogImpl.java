package com.beyondzy.demo.service.skill.impl;

import com.beyondzy.demo.enums.skill.SkillTargetKind;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mcp.McpServer;
import com.beyondzy.demo.mcp.McpToolDefinition;
import com.beyondzy.demo.service.skill.SkillResourceCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 平台能力来自聊天里实际注册的工具。MCP 来自已装配的 McpServer，发现失败即目录不可用。 */
@Service
public class SkillResourceCatalogImpl implements SkillResourceCatalog {
    private static final List<SkillResourceOption> PLATFORMS = List.of(
            new SkillResourceOption("PLATFORM", null, "get_current_time", "读取当前时间", List.of("clock.read"), true),
            new SkillResourceOption("PLATFORM", null, "get_current_user", "查询当前登录用户", List.of("user.read"), true));

    private final List<McpServer> servers;

    @Autowired
    public SkillResourceCatalogImpl(ObjectProvider<McpServer> servers) {
        this(servers.orderedStream().toList());
    }

    public SkillResourceCatalogImpl(List<McpServer> servers) {
        this.servers = servers == null ? List.of() : List.copyOf(servers);
    }

    @Override
    public SkillResourcePage page(String kind, String query, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new SkillException(400, "INVALID_ARGUMENT", "分页参数不合法");
        SkillTargetKind target = SkillTargetKind.parse(kind);
        if (target == SkillTargetKind.UNRESOLVED) {
            throw new SkillException(400, "INVALID_ARGUMENT", "不能查询未解析目标");
        }
        List<SkillResourceOption> matched = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (SkillResourceOption option : capture(target == SkillTargetKind.MCP_TOOL).options()) {
            if (!option.targetKind().equals(target.name()) || !option.available()) continue;
            if (!needle.isEmpty() && !haystack(option).contains(needle)) continue;
            matched.add(option);
        }
        matched.sort(Comparator.comparing(SkillResourceOption::toolKey));
        int from = Math.min((page - 1) * size, matched.size());
        int to = Math.min(from + size, matched.size());
        return new SkillResourcePage(List.copyOf(matched.subList(from, to)), matched.size(), page, size);
    }

    @Override
    public CatalogView capture(boolean includeMcp) {
        List<SkillResourceOption> options = new ArrayList<>(PLATFORMS);
        if (includeMcp) options.addAll(mcpOptions());
        return new CatalogView(List.copyOf(options));
    }

    private List<SkillResourceOption> mcpOptions() {
        List<SkillResourceOption> options = new ArrayList<>();
        for (McpServer server : servers) {
            List<McpToolDefinition> tools;
            try {
                tools = server.listTools();
            } catch (RuntimeException error) {
                throw new SkillException(503, "RESOURCE_CATALOG_UNAVAILABLE", "工具目录暂不可用");
            }
            if (tools == null) {
                throw new SkillException(503, "RESOURCE_CATALOG_UNAVAILABLE", "工具目录暂不可用");
            }
            for (McpToolDefinition tool : tools) {
                if (tool == null || tool.name() == null || tool.name().isBlank()) continue;
                String display = tool.description() == null || tool.description().isBlank()
                        ? tool.name()
                        : tool.description();
                options.add(new SkillResourceOption(
                        "MCP_TOOL", server.id(), tool.name(), display, List.of(), true));
            }
        }
        return options;
    }

    private static String haystack(SkillResourceOption option) {
        String capabilities = option.capabilityKeys() == null ? "" : String.join(" ", option.capabilityKeys());
        return (option.toolKey() + " " + option.displayName() + " " + option.providerKey() + " " + capabilities)
                .toLowerCase(Locale.ROOT);
    }
}
