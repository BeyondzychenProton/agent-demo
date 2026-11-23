package com.beyondzy.demo.web.skill.res;

import java.time.LocalDateTime;
import java.util.List;

/** 管理端技能列表。不含 SKILL.md 正文。 */
public record SkillAdminPageResponse(List<Item> items, long total, int page, int size) {
    public record Item(
            String name,
            String description,
            String status,
            String visibleScope,
            Long ownerDeptId,
            String ownerDeptName,
            int resourceCount,
            boolean bound,
            LocalDateTime updatedAt) {}
}
