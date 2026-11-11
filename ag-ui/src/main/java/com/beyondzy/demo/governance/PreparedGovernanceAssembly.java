package com.beyondzy.demo.governance;

import io.agentscope.core.tool.Toolkit;

/** 一次成功准备的治理装配：工具注册表、Skill 提示词与版本校验值。 */
public record PreparedGovernanceAssembly(
        GovernanceSnapshot snapshot,
        Toolkit toolkit,
        GovernanceToolMount mount,
        String skillsSection,
        String etag) {

    public String snapshotId() {
        return snapshot.snapshotId();
    }
}
