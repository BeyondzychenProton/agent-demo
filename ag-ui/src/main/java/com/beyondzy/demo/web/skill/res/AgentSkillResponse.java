package com.beyondzy.demo.web.skill.res;

import java.util.List;

/** 当前唯一智能体和它绑着的技能。 */
public record AgentSkillResponse(String agentId, String title, List<BoundSkill> skills) {
    public record BoundSkill(String name, String description, String status, String visibleScope,
            String versionId, String platformVersion, String versionNo) {}
}
