package com.beyondzy.demo.web.skill.res;

import java.time.LocalDateTime;

/** 技能列表中的一行，不含正文。 */
public record SkillSummaryResponse(
        String name,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int resourceCount) {}
