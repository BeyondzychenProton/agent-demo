package com.beyondzy.demo.web.skill.res;

import java.time.LocalDateTime;

/** 技能列表中的一行，不含正文。ownerUserId=0 表示平台公共技能。 */
public record SkillSummaryResponse(
        String name,
        String description,
        long ownerUserId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int resourceCount) {}
