package com.beyondzy.demo.service.skill.impl;

import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.contract.Pages;
import com.beyondzy.demo.domain.skill.SkillManagementRow;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillManagementMapper;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionMapper;
import com.beyondzy.demo.service.skill.SkillAdminService;
import com.beyondzy.demo.service.skill.SkillManagementService;
import com.beyondzy.demo.service.skill.SkillVersionService;
import com.beyondzy.demo.service.skill.support.SkillSupport;
import com.beyondzy.demo.service.skill.support.SkillTimes;
import com.beyondzy.demo.service.skill.view.SkillManagementView.Counts;
import com.beyondzy.demo.service.skill.view.SkillManagementView.Item;
import com.beyondzy.demo.service.skill.view.SkillManagementView.Overview;
import com.beyondzy.demo.service.skill.view.SkillManagementView.Page;
import com.beyondzy.demo.service.skill.view.SkillVersionView;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 页面所需目录、当前生效信息和草稿信息，版本正文仍由版本服务维护。 */
@Service
public class SkillManagementServiceImpl implements SkillManagementService {
    private final SkillManagementMapper catalog;
    private final SkillMapper skills;
    private final SkillBaselineMigrator baselines;
    private final SkillVersionService versions;
    private final SkillAdminService admin;
    private final SkillVersionMapper versionRows;

    public SkillManagementServiceImpl(SkillManagementMapper catalog, SkillMapper skills,
            SkillBaselineMigrator baselines, SkillVersionService versions, SkillAdminService admin,
            SkillVersionMapper versionRows) {
        this.catalog = catalog;
        this.skills = skills;
        this.baselines = baselines;
        this.versions = versions;
        this.admin = admin;
        this.versionRows = versionRows;
    }

    @Override
    @Transactional(readOnly = true)
    public Page page(String query, String state, String resource, int page, int size) {
        if (!Set.of("all", "enabled", "disabled", "draft").contains(state)
                || !Set.of("all", "mapped", "pending").contains(resource) || !Pages.valid(page, size)) {
            throw new SkillException(400, "INVALID_ARGUMENT", "筛选或分页参数不合法");
        }
        String q = query == null || query.isBlank() ? null
                : query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        Counts counts = new Counts(catalog.count(q, "all", resource), catalog.count(q, "enabled", resource),
                catalog.count(q, "disabled", resource), catalog.count(q, "draft", resource));
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SkillManagementRow> queryPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size, false);
        catalog.page(queryPage, q, state, resource);
        List<Item> items = queryPage.getRecords().stream()
                .map(row -> item(row, "draft".equals(state))).toList();
        long total = switch (state) {
            case "enabled" -> counts.enabled();
            case "disabled" -> counts.disabled();
            case "draft" -> counts.draft();
            default -> counts.all();
        };
        return new Page(items, total, page, size, counts);
    }

    @Override
    public Overview overview(String name) {
        SkillManagementRow row = catalog.exact(null, "all", name);
        if (row == null) {
            throw new SkillException(404, "技能不存在");
        }
        Item item = item(row, false);
        return new Overview(item, item.currentVersionId() == null ? null : versions.get(Long.parseLong(item.currentVersionId())),
                admin.agent(SkillJdbc.SESSION_AGENT));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillVersionView edit(long operatorId, String name) {
        var skill = skills.findGoverned(name);
        Long current = skill == null ? null : baselines.ensure(name, operatorId);
        // 锁定读取看到等待期间提交的草稿，避免 MySQL 可重复读的旧快照导致重复复制。
        var draft = versionRows.latestDraftLocked(name);
        if (draft != null) {
            return versions.get(draft.getId());
        }
        if (current == null) {
            throw new SkillException(404, "技能不存在");
        }
        return versions.create(operatorId, new SkillVersionService.CreateRequest(
                name, current.toString(), null, null, null, null, null));
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
