package com.beyondzy.demo.enums.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import com.beyondzy.demo.exception.skill.SkillException;

/** 内容处理。PRESERVE_PACKAGE 保留包内文件；REFERENCE_ONLY 按参考资料理解。 */
@Schema(description = "内容处理。PRESERVE_PACKAGE 保留包内文件；REFERENCE_ONLY 按参考资料理解。")
public enum SkillContentPolicy {
    PRESERVE_PACKAGE,
    REFERENCE_ONLY;

    public static SkillContentPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SkillException(400, "INVALID_ARGUMENT", "内容策略不合法");
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException error) {
            throw new SkillException(400, "INVALID_ARGUMENT", "内容策略不合法");
        }
    }
}
