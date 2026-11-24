package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.domain.skill.SkillManagementRow;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillManagementMapper;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionMapper;
import com.beyondzy.demo.service.skill.impl.SkillBaselineMigrator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 页面所需目录、当前生效信息和草稿信息，版本正文仍由版本服务维护。 */
@Service
public class SkillManagementService {
    public record Counts(long all, long enabled, long disabled, long draft) {}
    public record Item(String name, String displayName, String description, String status, String visibleScope,
            String ownerId, String ownerName, String updatedAt, String currentVersionId, String platformVersion,
            Integer versionNo, String draftVersionId, Integer draftVersionNo, int dependencyCount, int mappedCount, int boundCount,
            boolean dependenciesTracked) {}
    public record Page(List<Item> items, long total, int page, int size, Counts counts) {}
    public record Overview(Item item, SkillVersionView current, SkillAdminView.Agent agent) {}

    private final SkillManagementMapper catalog;
    private final SkillMapper skills;
    private final SkillBaselineMigrator baselines;
    private final SkillVersionService versions;
    private final SkillAdminService admin;
    private final SkillVersionMapper versionRows;

    public SkillManagementService(SkillManagementMapper catalog, SkillMapper skills,
            SkillBaselineMigrator baselines, SkillVersionService versions, SkillAdminService admin, SkillVersionMapper versionRows) {
        this.catalog = catalog; this.skills = skills;
        this.baselines = baselines; this.versions = versions; this.admin = admin;
        this.versionRows = versionRows;
    }

    @Transactional(readOnly = true)
    public Page page(String query, String state, String resource, int page, int size) {
        if (!Set.of("all", "enabled", "disabled", "draft").contains(state)
                || !Set.of("all", "mapped", "pending").contains(resource) || page < 1 || size < 1 || size > 100)
            throw new SkillException(400, "INVALID_ARGUMENT", "筛选或分页参数不合法");
        String q = query == null || query.isBlank() ? null
                : query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        Counts counts = new Counts(catalog.count(q, "all", resource), catalog.count(q, "enabled", resource),
                catalog.count(q, "disabled", resource), catalog.count(q, "draft", resource));
        List<Item> items = catalog.page(q, state, resource, size, (long) (page - 1) * size).stream()
                .map(row -> item(row, "draft".equals(state))).toList();
        long total = switch (state) { case "enabled" -> counts.enabled(); case "disabled" -> counts.disabled();
            case "draft" -> counts.draft(); default -> counts.all(); };
        return new Page(items, total, page, size, counts);
    }

    public Overview overview(String name) {
        SkillManagementRow row = catalog.exact(null, "all", name);
        if (row == null) throw new SkillException(404, "技能不存在");
        Item item = item(row, false);
        return new Overview(item, item.currentVersionId() == null ? null : versions.get(Long.parseLong(item.currentVersionId())),
                admin.agent(com.beyondzy.demo.config.skill.SkillJdbc.SESSION_AGENT));
    }

    @Transactional
    public SkillVersionView edit(long operatorId, String name) {
        var skill = skills.findGoverned(name);
        Long current = skill == null ? null : baselines.ensure(name, operatorId);
        // 锁定读取看到等待期间提交的草稿，避免 MySQL 可重复读的旧快照导致重复复制。
        var draft = versionRows.latestDraftLocked(name);
        if (draft != null) return versions.get(draft.getId());
        if (current == null) throw new SkillException(404, "技能不存在");
        return versions.create(operatorId, new SkillVersionService.CreateRequest(name, current.toString(), null, null, null, null, null));
    }

    private static Item item(SkillManagementRow row, boolean draft) {
        return new Item(row.getName(), row.getDisplayName(), row.getDescription(), row.getStatus(), row.getVisibleScope(),
                SkillSupport.id(row.getOwnerId()), row.getOwnerName(),
                draft || row.getStatus() == null ? SkillTimes.format(row.getUpdatedAt())
                        : row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString(),
                SkillSupport.id(row.getCurrentVersionId()),
                row.getPlatformVersion(), row.getVersionNo(), SkillSupport.id(row.getDraftVersionId()), row.getDraftVersionNo(),
                row.getDependencyCount(), row.getMappedCount(), row.getBoundCount(),
                draft || row.getStatus() == null || row.getPlatformVersion() != null);
    }
}
