package com.beyondzy.demo.web.skill.res;

/** 绑定页可勾选的技能。只包含当前操作人可见且启用的技能。 */
public record SkillOptionResponse(String name, String description, String currentVersionId,
        java.util.List<com.beyondzy.demo.service.skill.SkillAdminView.VersionOption> versions) {}
