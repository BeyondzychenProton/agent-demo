package com.beyondzy.demo.enums.skill;

import com.beyondzy.demo.exception.skill.SkillException;

/** 版本状态。与技能启停无关。本次不暴露归档操作。 */
public enum SkillVersionState {
    DRAFT,
    PUBLISHED,
    ARCHIVED;

    public static SkillVersionState parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SkillException(400, "INVALID_ARGUMENT", "版本状态不能为空");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException error) {
            throw new SkillException(400, "INVALID_ARGUMENT", "版本状态不合法");
        }
    }
}
