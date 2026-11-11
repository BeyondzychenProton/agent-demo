package com.beyondzy.demo.governance;

import java.util.List;

/** 治理 {@code GET /api/agent/snapshot/current} 里 Agent 需要保留的字段。 */
public record GovernanceSnapshot(
        String tenantId,
        String appId,
        String subject,
        String snapshotId,
        List<GovernanceToolView> tools,
        List<GovernanceSkillView> skills) {

    public GovernanceSnapshot {
        tools = tools == null ? List.of() : List.copyOf(tools);
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /** 兼容仅工具装配的旧调用点。 */
    public GovernanceSnapshot(
            String tenantId,
            String appId,
            String subject,
            String snapshotId,
            List<GovernanceToolView> tools) {
        this(tenantId, appId, subject, snapshotId, tools, List.of());
    }
}
