package com.beyondzy.demo.service.skill.view;

import java.util.List;

/** 技能管理页的目录行、页签计数和详情概览。 */
public final class SkillManagementView {
    private SkillManagementView() {}

    public record Counts(long all, long enabled, long disabled, long draft) {}

    public record Item(String name, String displayName, String description, String status, String visibleScope,
            String ownerId, String ownerName, String updatedAt, String currentVersionId, String platformVersion,
            Integer versionNo, String draftVersionId, Integer draftVersionNo, int dependencyCount, int mappedCount,
            int boundCount, boolean dependenciesTracked) {}

    public record Page(List<Item> items, long total, int page, int size, Counts counts) {}

    public record Overview(Item item, SkillVersionView current, SkillAdminView.Agent agent) {}
}
