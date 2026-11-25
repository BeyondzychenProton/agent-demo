package com.beyondzy.demo.enums.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import com.beyondzy.demo.exception.skill.SkillException;

/** 入口处理。KEEP_ORIGINAL 保留原包入口；PLATFORM_ENTRY 生成平台入口。 */
@Schema(description = "入口处理。KEEP_ORIGINAL 保留原包入口；PLATFORM_ENTRY 生成平台入口。")
public enum SkillEntryPolicy {
    KEEP_ORIGINAL,
    PLATFORM_ENTRY;

    public static SkillEntryPolicy parse(String raw) {
        return parse(raw, "入口策略不合法");
    }

    static SkillEntryPolicy parse(String raw, String message) {
        if (raw == null || raw.isBlank()) throw new SkillException(400, "INVALID_ARGUMENT", message);
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException error) {
            throw new SkillException(400, "INVALID_ARGUMENT", message);
        }
    }
}
