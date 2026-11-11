package com.beyondzy.demo.governance;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/** Reads only the resources listed in the snapshot bound to this toolkit. */
public final class GovernanceSkillResources {

    private final GovernanceGateway gateway;
    private final GovernanceSnapshot snapshot;

    public GovernanceSkillResources(GovernanceGateway gateway, GovernanceSnapshot snapshot) {
        this.gateway = gateway;
        this.snapshot = snapshot;
    }

    @Tool(name = "read_governance_skill_resource", description = "读取当前治理 Skill 的 Markdown 参考文件", readOnly = true)
    public ToolResultBlock readResource(
            @ToolParam(name = "skillId", description = "当前 Skill ID") String skillId,
            @ToolParam(name = "path", description = "清单中的 Skill 根目录相对路径") String path) {
        boolean listed = snapshot.skills().stream()
                .anyMatch(skill -> java.util.Objects.equals(skill.skillId(), skillId) && skill.resourcePaths().contains(path));
        if (!listed) {
            throw new IllegalArgumentException("当前快照未列出该 Skill 资源");
        }
        String text = gateway.fetchResource(snapshot.snapshotId(), skillId, path);
        return ToolResultBlock.text(text).withState(ToolResultState.SUCCESS);
    }
}
