package com.beyondzy.demo.enums.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import com.beyondzy.demo.exception.skill.SkillException;

/** 导入批次状态。CONFIRMED、CANCELLED、EXPIRED 是终态。 */
@Schema(description = "导入批次状态。CONFIRMED、CANCELLED、EXPIRED 是终态。")
public enum SkillImportState {
    UPLOADED,
    CHECKED,
    CONFIRMED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean open() {
        return this == UPLOADED || this == CHECKED || this == FAILED;
    }

    public static SkillImportState parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SkillException(400, "INVALID_ARGUMENT", "导入状态不能为空");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException error) {
            throw new SkillException(400, "INVALID_ARGUMENT", "导入状态不合法");
        }
    }
}
