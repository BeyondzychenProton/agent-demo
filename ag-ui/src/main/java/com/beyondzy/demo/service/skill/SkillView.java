package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.enums.skill.SkillFileKind;
import java.time.LocalDateTime;
import java.util.List;

/** 单个技能。skillContent 与资源正文只在明确要求时填充。 */
public record SkillView(
        String name,
        String description,
        String source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String skillContent,
        List<SkillResource> resources) {
    public record SkillResource(String path, SkillFileKind kind, String content) {}
}
