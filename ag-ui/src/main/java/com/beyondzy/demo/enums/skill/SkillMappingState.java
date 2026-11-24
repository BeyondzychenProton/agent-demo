package com.beyondzy.demo.enums.skill;

import com.beyondzy.demo.exception.skill.SkillException;

/** 映射是否已被管理员确认。建议映射不等于确认。 */
public enum SkillMappingState {
    SUGGESTED,
    CONFIRMED,
    PENDING;

    public static SkillMappingState parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SkillException(400, "INVALID_ARGUMENT", "映射状态不合法");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException error) {
            throw new SkillException(400, "INVALID_ARGUMENT", "映射状态不合法");
        }
    }
}