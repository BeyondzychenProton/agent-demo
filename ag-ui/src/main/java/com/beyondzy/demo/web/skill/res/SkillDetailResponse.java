package com.beyondzy.demo.web.skill.res;

import java.time.LocalDateTime;
import java.util.List;

/** 技能详情。skillContent 仅在 includeBodies=true 时返回。 */
public record SkillDetailResponse(
        String name,
        String description,
        String source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String skillContent,
        List<SkillResourceResponse> resources) {}
