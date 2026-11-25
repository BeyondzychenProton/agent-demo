package com.beyondzy.demo.config.skill;

import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.domain.skill.SkillVersionBean;
import com.beyondzy.demo.domain.skill.SkillVersionFileBean;
import com.beyondzy.demo.enums.skill.SkillFileKind;
import com.beyondzy.demo.enums.skill.SkillVersionState;
import com.beyondzy.demo.mapper.skill.AgentSkillMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionFileMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionMapper;
import com.beyondzy.demo.service.skill.support.SkillJson;
import com.beyondzy.demo.service.skill.support.SkillSupport;
import com.beyondzy.demo.service.skill.SkillVisibilityService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 共享仓库不缓存用户目录。每轮在 RuntimeContext 中保存固定版本内容快照。 */
public final class VersionedSkillRepository implements RuntimeContextSkillRepository {
    public record Snapshot(List<AgentSkill> skills) {
        public Snapshot { skills = List.copyOf(skills); }
    }

    private final AgentSkillRepository legacy;
    private final AgentSkillMapper bindings;
    private final SkillVersionMapper versions;
    private final SkillVersionFileMapper files;
    private final SkillVisibilityService visibility;

    public VersionedSkillRepository(AgentSkillRepository legacy, AgentSkillMapper bindings,
            SkillVersionMapper versions, SkillVersionFileMapper files, SkillVisibilityService visibility) {
        this.legacy = legacy;
        this.bindings = bindings;
        this.versions = versions;
        this.files = files;
        this.visibility = visibility;
    }

    public Snapshot capture(String agentId, String userId) {
        Set<String> allowed = Set.copyOf(visibility.namesFor(agentId, userId));
        List<SkillBean> selected = new ArrayList<>();
        List<Long> versionIds = new ArrayList<>();
        for (SkillBean binding : bindings.listBound(agentId)) {
            if (!allowed.contains(binding.getName()) || !SkillBean.STATUS_ENABLED.equals(binding.getStatus())) continue;
            selected.add(binding);
            if (binding.getVersionId() != null) versionIds.add(binding.getVersionId());
        }
        Map<Long, SkillVersionBean> versionById = new HashMap<>();
        if (!versionIds.isEmpty()) {
            for (SkillVersionBean version : versions.listByIds(versionIds)) {
                versionById.putIfAbsent(version.getId(), version);
            }
        }
        List<Long> contentIds = new ArrayList<>();
        for (SkillBean binding : selected) {
            SkillVersionBean version = published(binding, versionById);
            if (version != null) contentIds.add(version.getId());
        }
        Map<Long, List<SkillVersionFileBean>> filesByVersion = new HashMap<>();
        if (!contentIds.isEmpty()) {
            for (SkillVersionFileBean file : files.listContentByVersions(contentIds)) {
                filesByVersion.computeIfAbsent(file.getVersionId(), ignored -> new ArrayList<>()).add(file);
            }
        }
        List<AgentSkill> result = new ArrayList<>();
        Long parsedUser = null;
        for (SkillBean binding : selected) {
            if (binding.getVersionId() == null) {
                // 尚未迁移的旧绑定只读运行内容；发布前会先快照并固定，不在启动期写数据。
                AgentSkill skill = legacy.getSkill(binding.getName());
                if (skill != null) result.add(skill);
                continue;
            }
            SkillVersionBean version = published(binding, versionById);
            if (version == null) continue;
            if (parsedUser == null) parsedUser = Long.parseLong(userId);
            SkillBean placement = new SkillBean();
            placement.setVisibleScope(version.getVisibleScope());
            placement.setOwnerDeptId(version.getOwnerDeptId());
            placement.setCreateBy(version.getCreatedBy());
            if (!visibility.visibleTo(placement, parsedUser)) continue;
            LinkedHashMap<String, String> resources = new LinkedHashMap<>();
            for (SkillVersionFileBean file : filesByVersion.getOrDefault(version.getId(), List.of())) {
                if ((SkillFileKind.REFERENCE.name().equals(file.getFileKind()) || SkillFileKind.SCRIPT.name().equals(file.getFileKind()))
                        && SkillSupport.utf8(file.getContent())) {
                    resources.put(file.getResourcePath(), new String(file.getContent(), StandardCharsets.UTF_8));
                }
            }
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            try {
                java.util.Map<?, ?> preserved = SkillJson.read(version.getMetadataJson(), java.util.Map.class);
                if (preserved != null) preserved.forEach((key, value) -> {
                    if (key instanceof String text) metadata.put(text, value);
                });
            } catch (RuntimeException invalidMetadata) {
                // 与 JDBC 仓库一致：损坏的可选元数据不应让有效正文失效。
            }
            metadata.put("name", version.getSkillName());
            metadata.put("description", version.getDescription());
            result.add(new AgentSkill(metadata, version.getSkillContent(), resources, getSource()));
        }
        return new Snapshot(result);
    }

    private static SkillVersionBean published(SkillBean binding, Map<Long, SkillVersionBean> versionById) {
        if (binding.getVersionId() == null) return null;
        SkillVersionBean version = versionById.get(binding.getVersionId());
        if (version == null || !SkillVersionState.PUBLISHED.name().equals(version.getState())
                || !binding.getId().equals(version.getSkillId())
                || !binding.getName().equals(version.getSkillName())) return null;
        return version;
    }

    @Override public List<AgentSkill> getAllSkills(RuntimeContext context) {
        Snapshot snapshot = context == null ? null : context.get(Snapshot.class);
        return snapshot == null ? List.of() : snapshot.skills();
    }
    @Override public AgentSkill getSkill(String name) { return null; }
    @Override public List<String> getAllSkillNames() { return List.of(); }
    @Override public List<AgentSkill> getAllSkills() { return List.of(); }
    @Override public boolean save(List<AgentSkill> skills, boolean force) { return false; }
    @Override public boolean delete(String name) { return false; }
    @Override public boolean skillExists(String name) { return false; }
    @Override public AgentSkillRepositoryInfo getRepositoryInfo() { return legacy.getRepositoryInfo(); }
    @Override public String getSource() { return SkillJdbc.SOURCE; }
    @Override public void setWriteable(boolean writeable) {
        if (writeable) throw new UnsupportedOperationException("运行技能仓库只读");
    }
    @Override public boolean isWriteable() { return false; }
    @Override public void close() { legacy.close(); }
}
