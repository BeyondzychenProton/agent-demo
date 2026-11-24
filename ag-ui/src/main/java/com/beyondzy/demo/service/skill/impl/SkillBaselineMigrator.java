package com.beyondzy.demo.service.skill.impl;

import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.domain.skill.SkillResourceBean;
import com.beyondzy.demo.domain.skill.SkillVersionBean;
import com.beyondzy.demo.domain.skill.SkillVersionFileBean;
import com.beyondzy.demo.enums.skill.SkillFileKind;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionFileMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionMapper;
import com.beyondzy.demo.mapper.skill.SkillReleaseMapper;
import com.beyondzy.demo.mapper.skill.AgentSkillMapper;
import com.beyondzy.demo.service.skill.SkillDocuments;
import com.beyondzy.demo.service.skill.SkillJson;
import com.beyondzy.demo.service.skill.SkillSourceRules;
import com.beyondzy.demo.service.skill.SkillSupport;
import com.beyondzy.demo.service.skill.SkillTimes;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把当前运行技能收成可重跑的基线发布快照。
 *
 * <p>不伪造原包或包版本。仅补生效指针和缺失的固定引用，不改运行内容或启停。
 */
@Service
public class SkillBaselineMigrator {
    private final SkillMapper skills;
    private final SkillResourceMapper resources;
    private final SkillVersionMapper versions;
    private final SkillVersionFileMapper files;
    private final SkillReleaseMapper releases;
    private final AgentSkillMapper bindings;

    public SkillBaselineMigrator(
            SkillMapper skills, SkillResourceMapper resources, SkillVersionMapper versions, SkillVersionFileMapper files,
            SkillReleaseMapper releases, AgentSkillMapper bindings) {
        this.skills = skills;
        this.resources = resources;
        this.versions = versions;
        this.files = files;
        this.releases = releases;
        this.bindings = bindings;
    }

    @Transactional
    public int migrate(long fallbackUserId) {
        int created = 0;
        for (SkillBean skill : skills.listAll()) {
            if (releases.current(skill.getId()) != null) {
                bindings.pinMissing(skill.getId(), releases.current(skill.getId()));
                continue;
            }
            ensure(skill.getName(), fallbackUserId);
            created++;
        }
        return created;
    }

    /** 与发布保持相同锁顺序；即使已有历史版本，也必须快照实际运行内容。 */
    @Transactional
    public long ensure(String name, long fallbackUserId) {
        versions.lockBySkillName(name);
        SkillBean skill = skills.lockByName(name);
        if (skill == null) throw new SkillException(404, "技能不存在");
        Long current = releases.current(skill.getId());
        if (current != null) {
            bindings.pinMissing(skill.getId(), current);
            return current;
        }
        LocalDateTime now = SkillTimes.now();
        String description = skill.getDescription() == null || skill.getDescription().isBlank()
                ? skill.getName()
                : skill.getDescription();
        String markdown = skill.getSkillContent() == null ? "" : skill.getSkillContent();
        SkillVersionBean version = new SkillVersionBean();
        version.setSkillName(skill.getName());
        version.setSkillId(skill.getId());
        version.setVersionNo(versions.maxVersionNo(name) + 1);
        version.setState("PUBLISHED");
        version.setDescription(description);
        version.setSkillContent(markdown);
        version.setMetadataJson(skill.getMetadataJson() == null
                ? SkillSupport.metadata(skill.getName(), description)
                : skill.getMetadataJson());
        SkillSourceRules rules = new SkillSourceRules();
        rules.baseline = true;
        version.setSourceRulesJson(SkillJson.write(rules));
        version.setVisibleScope(skill.getVisibleScope() == null ? "0" : skill.getVisibleScope());
        version.setOwnerDeptId(skill.getOwnerDeptId());
        version.setCreatedBy(skill.getCreateBy() == null ? fallbackUserId : skill.getCreateBy());
        version.setRevision(0L);
        version.setGmtCreate(now);
        version.setGmtModified(now);
        versions.insertVersion(version);
        write(version.getId(), "SKILL.md", SkillFileKind.SKILL, markdown.getBytes(StandardCharsets.UTF_8), now);
        if (skill.getId() != null) {
            for (SkillResourceBean resource : resources.findContents(skill.getId())) {
                String path = resource.getResourcePath();
                byte[] body = resource.getResourceContent() == null
                        ? new byte[0]
                        : resource.getResourceContent().getBytes(StandardCharsets.UTF_8);
                SkillFileKind kind = SkillDocuments.kind(path);
                write(version.getId(), path, kind, body, now);
            }
        }
        releases.point(skill.getId(), version.getId());
        bindings.pinMissing(skill.getId(), version.getId());
        return version.getId();
    }

    private void write(long versionId, String path, SkillFileKind kind, byte[] body, LocalDateTime now) {
        SkillVersionFileBean file = new SkillVersionFileBean();
        file.setVersionId(versionId);
        file.setResourcePath(path);
        file.setFileKind(kind.name());
        file.setContent(body);
        file.setContentSha256(SkillSupport.sha256(body));
        file.setByteSize(body.length);
        file.setGmtCreate(now);
        file.setGmtModified(now);
        files.insertFile(file);
    }
}
