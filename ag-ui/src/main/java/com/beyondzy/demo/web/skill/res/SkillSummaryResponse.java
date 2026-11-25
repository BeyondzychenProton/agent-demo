package com.beyondzy.demo.web.skill.res;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 技能列表中的一行，不含正文。 */
@Schema(description = "技能列表中的一行，不含正文")
public record SkillSummaryResponse(
        @Schema(description = "技能名称") String name,
        @Schema(description = "技能说明") String description,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt,
        @Schema(description = "资源数量") int resourceCount) {}
