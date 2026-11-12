package com.beyondzy.demo.service.skill;

import java.time.LocalDateTime;
import java.util.List;

/** 技能列表页。不含 SKILL.md 正文。 */
public record SkillPage(List<SkillSummary> items, long total, int page, int size) {
    public record SkillSummary(
            String name,
            String description,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            int resourceCount) {}
}
