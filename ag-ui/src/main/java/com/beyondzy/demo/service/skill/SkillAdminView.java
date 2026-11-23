package com.beyondzy.demo.service.skill;

import java.time.LocalDateTime;
import java.util.List;

/** 管理端读到的技能和绑定。详情里的 content 是剥掉 frontmatter 的正文。 */
public final class SkillAdminView {
    private SkillAdminView() {}

    public record Page(List<Item> items, long total, int page, int size) {}

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

    public record Detail(
            String name,
            String description,
            String content,
            String status,
            String visibleScope,
            Long ownerDeptId,
            String ownerDeptName,
            boolean bound,
            LocalDateTime updatedAt,
            List<File> files) {}

    public record File(String path, String content) {}

    public record Option(String name, String description) {}

    public record Agent(
            String agentId,
            String title,
            List<BoundSkill> skills) {}

    public record BoundSkill(String name, String description, String status, String visibleScope) {}
}
