package com.beyondzy.demo.governance;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 Snapshot {@code skills[]} 解析成系统提示词片段。
 *
 * <p>内联 {@code instructions} 优先；仅有 {@code instructionsRef} 时再 GET 正文。
 * 绑定提示优先映射到本进程已注册的工具名，避免模型去猜 URI。
 */
public final class GovernanceSkillPrompt {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceSkillPrompt.class);

    private GovernanceSkillPrompt() {}

    public static String resolveInstructions(
            GovernanceSkillView skill, GovernanceGateway gateway, String snapshotId) {
        if (skill == null) return "";
        if (skill.instructions() != null && !skill.instructions().isBlank()) {
            return skill.instructions().trim();
        }
        if (skill.instructionsRef() == null || skill.instructionsRef().isBlank()) {
            return "";
        }
        if (gateway == null || snapshotId == null || snapshotId.isBlank() || skill.skillId() == null) {
            return "";
        }
        return gateway.fetchInstructions(snapshotId, skill.skillId());
    }

    public static String buildSection(
            List<GovernanceSkillView> skills,
            Map<String, String> uriToRegisteredName,
            GovernanceGateway gateway,
            String snapshotId) {
        if (skills == null || skills.isEmpty()) return "";
        Map<String, String> names = uriToRegisteredName == null ? Map.of() : uriToRegisteredName;
        StringBuilder out = new StringBuilder();
        out.append("\n## Governance skills\n");
        out.append("以下技能说明来自治理 Snapshot；工具调用仍走已注册执行器，不要启用本地动态 Skill。\n");
        for (GovernanceSkillView skill : skills) {
            if (skill == null || skill.skillId() == null || skill.skillId().isBlank()) continue;
            String body;
            try {
                body = resolveInstructions(skill, gateway, snapshotId);
            } catch (RuntimeException error) {
                logger.warn("拉取 Skill {} 正文失败：{}", skill.skillId(), error.toString());
                body = "";
            }
            out.append("\n### ").append(skill.skillId());
            if (skill.name() != null && !skill.name().isBlank()) {
                out.append(" (").append(skill.name().trim()).append(')');
            }
            out.append('\n');
            if (body != null && !body.isBlank()) {
                out.append(body.trim()).append('\n');
            }
            if (!skill.resourcePaths().isEmpty()) {
                out.append("通过 read_governance_skill_resource 读取本 Skill 的相对路径：")
                        .append(String.join(", ", skill.resourcePaths())).append('\n');
            }
            if (skill.toolBindings() != null && !skill.toolBindings().isEmpty()) {
                out.append("可用工具绑定：\n");
                for (GovernanceSkillBinding binding : skill.toolBindings()) {
                    if (binding == null || binding.toolUri() == null || binding.toolUri().isBlank()) continue;
                    String registered = names.get(binding.toolUri());
                    String alias = binding.alias() == null || binding.alias().isBlank()
                            ? binding.modelName()
                            : binding.alias();
                    out.append("- ");
                    if (registered != null && !registered.isBlank()) {
                        out.append("registered=`").append(registered).append('`');
                    } else {
                        out.append("uri=`").append(binding.toolUri()).append('`');
                    }
                    if (alias != null && !alias.isBlank()) {
                        out.append(" alias=`").append(alias).append('`');
                    }
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }
}
