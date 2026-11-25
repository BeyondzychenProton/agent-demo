package com.beyondzy.demo.service.skill.view;

import java.time.LocalDateTime;

/** ?????????????? */
public record SkillSummary(
        String name,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int resourceCount) {}
