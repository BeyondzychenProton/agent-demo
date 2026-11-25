package com.beyondzy.demo.enums.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import com.beyondzy.demo.exception.skill.SkillException;

/** 依赖要落到的目标。UNRESOLVED 表示尚未接入。 */
@Schema(description = "依赖要落到的目标。UNRESOLVED 表示尚未接入。")
public enum SkillTargetKind {
    MCP_TOOL,
    PLATFORM,
    UNRESOLVED;

    public static SkillTargetKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SkillException(400, "INVALID_ARGUMENT", "依赖目标类型不合法");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException error) {
            throw new SkillException(400, "INVALID_ARGUMENT", "依赖目标类型不合法");
        }
    }
}
