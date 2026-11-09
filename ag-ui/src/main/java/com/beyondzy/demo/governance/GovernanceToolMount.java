package com.beyondzy.demo.governance;

import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动时把一整份 Snapshot 装进 Toolkit。
 *
 * <p>本轮不支持运行中在线刷新或 live Toolkit 切换；更新工具需在当前任务结束后重启进程，
 * 由新的 {@code ChatSessions} 再拉一份 Snapshot 并重新装配。成功装配后不可再次修改同一份 Toolkit。
 */
public final class GovernanceToolMount {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceToolMount.class);
    private final Object lock = new Object();
    private final Toolkit toolkit;
    private final GovernanceGateway gateway;
    private List<GovernanceBoundTool> current = List.of();
    private String snapshotId;
    private boolean installed;

    public GovernanceToolMount(Toolkit toolkit, GovernanceGateway gateway) {
        if (toolkit == null) throw new IllegalArgumentException("toolkit 不能为空");
        this.toolkit = toolkit;
        this.gateway = gateway;
    }

    /**
     * 将 Snapshot 工具注册到构造时给定的 Toolkit。仅允许成功调用一次；
     * 再次调用会拒绝，避免运行中替换正在使用的工具定义或执行器。
     */
    public void install(GovernanceSnapshot snapshot) {
        if (snapshot == null || snapshot.snapshotId() == null || snapshot.snapshotId().isBlank()) {
            throw new IllegalArgumentException("snapshotId 不能为空");
        }
        List<GovernanceBoundTool> next = compile(snapshot);
        synchronized (lock) {
            if (installed) {
                throw new IllegalStateException("治理工具已在启动时装配，运行中不可替换；请重启进程");
            }
            try {
                for (GovernanceBoundTool tool : next) toolkit.registerAgentTool(tool);
                current = List.copyOf(next);
                snapshotId = snapshot.snapshotId();
                installed = true;
            } catch (RuntimeException error) {
                for (GovernanceBoundTool tool : next) toolkit.removeTool(tool.getName());
                throw error;
            }
        }
        logger.info("已装配治理 Snapshot {}，工具 {} 个", snapshot.snapshotId(), next.size());
    }

    public String snapshotId() {
        return snapshotId;
    }

    public List<String> registeredNames() {
        synchronized (lock) {
            return current.stream().map(GovernanceBoundTool::getName).toList();
        }
    }

    private List<GovernanceBoundTool> compile(GovernanceSnapshot snapshot) {
        List<GovernanceToolView> usable = new ArrayList<>();
        for (GovernanceToolView tool : snapshot.tools()) {
            if (tool.uri() == null || tool.uri().isBlank() || tool.name() == null || tool.name().isBlank()) {
                logger.warn("跳过缺少 uri 或 name 的治理工具");
                continue;
            }
            if (tool.inputSchema() == null || tool.inputSchema().isEmpty()) {
                logger.warn("跳过缺少 inputSchema 的治理工具 {}", tool.name());
                continue;
            }
            usable.add(tool);
        }
        List<GovernanceToolNames.Assignment> names = GovernanceToolNames.assign(usable);
        List<GovernanceBoundTool> tools = new ArrayList<>();
        for (int i = 0; i < usable.size(); i++) {
            tools.add(new GovernanceBoundTool(gateway, names.get(i).registeredName(), snapshot, usable.get(i)));
        }
        return tools;
    }
}
