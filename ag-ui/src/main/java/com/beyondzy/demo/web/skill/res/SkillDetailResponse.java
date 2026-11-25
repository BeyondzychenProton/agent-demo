package com.beyondzy.demo.web.skill.res;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 技能详情。skillContent 仅在 includeBodies=true 时返回。 */
@Schema(description = "技能详情。")
public record SkillDetailResponse(
        @Schema(description = "技能名") String name,
        @Schema(description = "技能说明") String description,
        @Schema(description = "来源") String source,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt,
        @Schema(description = "技能正文，仅在包含正文时返回") String skillContent,
        @Schema(description = "附属资源") List<SkillResourceResponse> resources) {}
