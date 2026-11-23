package com.beyondzy.demo.web.skill.req;

import java.util.List;

/** 创建或修改技能。修改时路径里的名字为准，这里的 name 不会改掉已有技能名。 */
public record SaveSkillRequest(
        String name,
        String description,
        String content,
        String visibleScope,
        Long ownerDeptId,
        String status,
        List<SkillFileRequest> files) {
    public record SkillFileRequest(String path, String content) {}
}
