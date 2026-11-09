package com.beyondzy.demo.governance;

import java.util.List;

/** 治理 {@code GET /api/agent/snapshot/current} 里 Agent 需要保留的字段。 */
public record GovernanceSnapshot(
        String tenantId,
        String appId,
        String subject,
        String snapshotId,
        List<GovernanceToolView> tools) {

    public GovernanceSnapshot {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
