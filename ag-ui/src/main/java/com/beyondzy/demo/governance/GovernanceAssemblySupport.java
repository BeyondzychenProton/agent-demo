package com.beyondzy.demo.governance;

import com.beyondzy.demo.tools.DocSearchTools;
import com.beyondzy.demo.tools.NoteTools;
import com.beyondzy.demo.tools.OrderTools;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 把 Snapshot 准备成可交给 Harness 的完整装配（本地工具 + 治理工具 + Skill 提示词）。 */
public final class GovernanceAssemblySupport {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceAssemblySupport.class);

    private GovernanceAssemblySupport() {}

    public static PreparedGovernanceAssembly prepare(
            Path workspace, GovernanceGateway gateway, GovernanceSnapshot snapshot, String etag) {
        if (workspace == null) throw new IllegalArgumentException("workspace 不能为空");
        if (gateway == null) throw new IllegalArgumentException("gateway 不能为空");
        if (snapshot == null || snapshot.snapshotId() == null || snapshot.snapshotId().isBlank()) {
            throw new IllegalArgumentException("snapshotId 不能为空");
        }
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        toolkit.registerTool(new DocSearchTools());
        toolkit.registerTool(new NoteTools(workspace.toAbsolutePath()));
        return prepare(toolkit, gateway, snapshot, etag, true);
    }

    public static PreparedGovernanceAssembly prepare(
            Toolkit toolkit, GovernanceGateway gateway, GovernanceSnapshot snapshot, String etag,
            boolean requireUserCredential) {
        if (snapshot.skills().stream().anyMatch(skill -> !skill.resourcePaths().isEmpty())) {
            toolkit.registerTool(new GovernanceSkillResources(gateway, snapshot));
        }
        GovernanceToolMount mount = new GovernanceToolMount(toolkit, gateway, requireUserCredential);
        mount.install(snapshot);
        String skillsSection = "";
        if (snapshot.skills() != null && !snapshot.skills().isEmpty() && mount.snapshotId() != null) {
            skillsSection = GovernanceSkillPrompt.buildSection(
                    snapshot.skills(), mount.registeredNameByUri(), gateway, mount.snapshotId());
        }
        logger.info(
                "已准备治理装配 snapshotId={} tools={} skillsSectionBlank={}",
                snapshot.snapshotId(),
                mount.registeredNames().size(),
                skillsSection == null || skillsSection.isBlank());
        return new PreparedGovernanceAssembly(snapshot, toolkit, mount, skillsSection, etag);
    }
}
