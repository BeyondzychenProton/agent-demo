package com.beyondzy.demo.service.skill;

import java.util.List;

/** 管理端保存技能时提交的字段。name 只在创建时使用。 */
public record SkillDraft(
        String name,
        String description,
        String content,
        String visibleScope,
        Long ownerDeptId,
        String status,
        List<SkillFile> files) {
    public SkillDraft {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public record SkillFile(String path, String content) {}
}
