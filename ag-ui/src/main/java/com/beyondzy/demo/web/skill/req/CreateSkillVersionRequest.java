package com.beyondzy.demo.web.skill.req;

import java.util.List;

/** 从已发布版本复制，或手工新建草稿。复制时忽略正文和文件。 */
public record CreateSkillVersionRequest(
        String skillName,
        String baseVersionId,
        String description,
        String content,
        String visibleScope,
        Long ownerDeptId,
        List<TextFile> files) {
    public record TextFile(String path, String content) {}
}
