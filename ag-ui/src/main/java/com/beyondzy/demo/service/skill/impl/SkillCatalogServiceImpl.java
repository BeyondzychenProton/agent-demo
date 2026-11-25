package com.beyondzy.demo.service.skill.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.beyondzy.demo.contract.PageResult;
import com.beyondzy.demo.contract.Pages;
import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.service.skill.SkillCatalogService;
import com.beyondzy.demo.service.skill.support.SkillDocuments;
import com.beyondzy.demo.service.skill.support.SkillUpload;
import com.beyondzy.demo.service.skill.view.SkillView;
import com.beyondzy.demo.service.skill.view.SkillSummary;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 旧技能目录。保存和删除不再调用仓库，避免只读仓库静默丢弃写入。 */
@Service
public class SkillCatalogServiceImpl implements SkillCatalogService {
    private static final String RETIRED = "写入口已收到管理端";
    private final AgentSkillRepository repository;
    private final SkillMapper skills;
    private final SkillResourceMapper resources;

    public SkillCatalogServiceImpl(
            AgentSkillRepository repository, SkillMapper skills, SkillResourceMapper resources) {
        this.repository = repository;
        this.skills = skills;
        this.resources = resources;
    }

    @Override
    public SkillView saveMarkdown(byte[] body, boolean overwrite) {
        throw new SkillException(410, RETIRED);
    }

    @Override
    public SkillView saveArchive(byte[] zip, boolean overwrite) {
        throw new SkillException(410, RETIRED);
    }

    @Override
    public SkillView saveFiles(List<SkillUpload> files, boolean overwrite) {
        throw new SkillException(410, RETIRED);
    }

    @Override
    public PageResult<SkillSummary> list(int page, int size) {
        if (!Pages.valid(page, size)) throw new SkillException(400, "分页参数不合法");
        Page<SkillBean> query = new Page<>(page, size);
        skills.page(query);
        List<SkillSummary> items = query.getRecords().stream()
                .map(SkillCatalogServiceImpl::summary)
                .toList();
        return new PageResult<>(items, query.getTotal(), page, size);
    }

    @Override
    public SkillView detail(String name, boolean includeBodies) {
        SkillBean row = requireRow(name);
        List<String> paths = row.getId() == null ? List.of() : resources.findPaths(row.getId());
        AgentSkill skill = includeBodies ? repository.getSkill(name) : null;
        List<SkillView.SkillResource> files = new ArrayList<>();
        for (String path : paths) {
            String content = skill == null ? null : skill.getResource(path);
            files.add(new SkillView.SkillResource(path, SkillDocuments.kind(path), content));
        }
        return new SkillView(
                row.getName(),
                row.getDescription(),
                row.getSource(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                skill == null ? null : skill.getSkillContent(),
                List.copyOf(files));
    }

    @Override
    public void delete(String name) {
        throw new SkillException(410, RETIRED);
    }

    private SkillBean requireRow(String name) {
        if (name == null || !SkillDocuments.NAME.matcher(name).matches()) {
            throw new SkillException(400, "技能名不合法");
        }
        SkillBean row = skills.findSummary(name);
        if (row == null) throw new SkillException(404, "技能不存在: " + name);
        return row;
    }

    private static SkillSummary summary(SkillBean row) {
        int count = row.getResourceCount() == null ? 0 : row.getResourceCount();
        return new SkillSummary(
                row.getName(), row.getDescription(), row.getCreatedAt(), row.getUpdatedAt(), count);
    }
}
