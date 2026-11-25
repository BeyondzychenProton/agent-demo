package com.beyondzy.demo.web.skill.res;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 技能分页。 */
@Schema(description = "技能分页")
public record SkillPageResponse(
        @Schema(description = "当前页技能") List<SkillSummaryResponse> items,
        @Schema(description = "总条数") long total,
        @Schema(description = "页码，从 1 开始") int page,
        @Schema(description = "每页条数") int size) {}
