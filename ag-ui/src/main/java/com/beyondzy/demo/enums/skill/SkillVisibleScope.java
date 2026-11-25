package com.beyondzy.demo.enums.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import com.beyondzy.demo.exception.skill.SkillException;

/** 技能行上的可见范围。本部门只比较部门 id，不沿父部门展开。 */
@Schema(description = "技能行上的可见范围。本部门只比较部门 id，不沿父部门展开。")
public enum SkillVisibleScope {
    PUBLIC("0"),
    DEPARTMENT("1"),
    OWNER("2");

    private final String code;

    SkillVisibleScope(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 缺省公开。无法识别的值直接拒绝，避免写成一个谁也看不见的范围。 */
    public static SkillVisibleScope parse(String raw) {
        if (raw == null || raw.isBlank()) return PUBLIC;
        for (SkillVisibleScope scope : values()) {
            if (scope.code.equals(raw)) return scope;
        }
        throw new SkillException(400, "可见范围不合法");
    }
}
