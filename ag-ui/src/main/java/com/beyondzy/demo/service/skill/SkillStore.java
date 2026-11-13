package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.domain.skill.SkillResourceBean;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.skill.AgentSkill;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能持久化（自管）。
 *
 * <p>为什么不再用框架的 {@code JdbcAgentSkillRepository}：它按 {@code namespace} 列分区，
 * 而本库的历史表没有该列（唯一键是 {@code name}）。本项目需要的是"按用户归属"，
 * 直接以 {@code owner_user_id} 表达，语义更直白，也能加索引。
 *
 * <p>可见性：{@code owner_user_id = 0} 平台公共；{@code owner_user_id = N} 仅用户 N。
 * 同名时用户层覆盖平台层（唯一键 (owner_user_id, name) 允许两层各存一份）。
 */
public class SkillStore {

    /** 平台公共技能的归属值。不要改成 null：唯一键里 NULL 不参与去重。 */
    public static final long PLATFORM_OWNER = 0L;

    public static final String SOURCE_PLATFORM = "platform";
    public static final String SOURCE_USER = "user";

    private final SkillMapper skills;
    private final SkillResourceMapper resources;
    private final ObjectMapper json;

    public SkillStore(SkillMapper skills, SkillResourceMapper resources, ObjectMapper json) {
        this.skills = skills;
        this.resources = resources;
        this.json = json;
    }

    /** 一条技能（含正文与附属文件）。 */
    public record SkillRecord(
            Long id,
            String name,
            String description,
            String skillContent,
            Map<String, Object> metadata,
            String source,
            long ownerUserId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Map<String, String> resources) {

        public boolean platform() {
            return ownerUserId == PLATFORM_OWNER;
        }
    }

    // ---------------- 读 ----------------

    /** 运行时加载：平台 + 本人，同名用户层优先。 */
    public List<SkillRecord> listVisible(long userId) {
        Map<String, SkillRecord> byName = new LinkedHashMap<>();
        for (SkillRecord record : listVisibleRaw(userId)) {
            // SQL 已按 name, owner_user_id DESC 排序，先到的（用户层）保留
            byName.putIfAbsent(record.name(), record);
        }
        return List.copyOf(byName.values());
    }

    public SkillRecord findVisible(long userId, String name) {
        return toRecord(skills.findVisibleFull(userId, name));
    }

    public SkillRecord findByOwner(long ownerUserId, String name) {
        return toRecord(skills.findByOwnerFull(ownerUserId, name));
    }

    public List<SkillBean> pageVisible(long userId, int limit, long offset) {
        return skills.pageVisible(userId, limit, offset);
    }

    public long countVisible(long userId) {
        return skills.countVisible(userId);
    }

    public List<SkillBean> pageAll(int limit, long offset) {
        return skills.pageAll(limit, offset);
    }

    public long countAll() {
        return skills.countAll();
    }

    public List<String> resourcePaths(long skillId) {
        return resources.findPaths(skillId);
    }

    // ---------------- 写 ----------------

    /**
     * 保存技能。
     *
     * @param ownerUserId 归属用户；0 = 平台公共
     * @param overwrite 同名（同归属）是否覆盖
     */
    public SkillRecord save(long ownerUserId, SkillDocuments.Parsed parsed, boolean overwrite) {
        SkillBean existing = skills.findByOwnerFull(ownerUserId, parsed.name());
        if (existing != null) {
            if (!overwrite) {
                throw new SkillException(409, "技能已存在: " + parsed.name());
            }
            skills.deleteByOwner(ownerUserId, parsed.name()); // 资源随外键级联删除
        }
        SkillBean row = new SkillBean();
        row.setName(parsed.name());
        row.setDescription(parsed.description());
        row.setSkillContent(parsed.skillContent());
        row.setSource(ownerUserId == PLATFORM_OWNER ? SOURCE_PLATFORM : SOURCE_USER);
        row.setOwnerUserId(ownerUserId);
        row.setMetadataJson(writeMetadata(parsed.metadata()));
        skills.insert(row);
        if (row.getId() == null) {
            throw new IllegalStateException("技能插入后未返回主键");
        }
        for (Map.Entry<String, String> resource : parsed.resources().entrySet()) {
            SkillResourceBean bean = new SkillResourceBean();
            bean.setId(row.getId());
            bean.setResourcePath(resource.getKey());
            bean.setResourceContent(resource.getValue());
            resources.insert(bean);
        }
        return findByOwner(ownerUserId, parsed.name());
    }

    /** 删除指定归属下的技能；资源级联删除。 */
    public boolean delete(long ownerUserId, String name) {
        return skills.deleteByOwner(ownerUserId, name) > 0;
    }

    // ---------------- 转换 ----------------

    /** 转成框架的 AgentSkill（运行时给 Agent 用）。 */
    public AgentSkill toAgentSkill(SkillRecord record) {
        AgentSkill.Builder builder = AgentSkill.builder()
                .name(record.name())
                .description(record.description())
                .skillContent(record.skillContent())
                .source(record.source());
        if (record.metadata() != null && !record.metadata().isEmpty()) {
            builder.metadata(record.metadata());
        }
        if (record.resources() != null && !record.resources().isEmpty()) {
            builder.resources(record.resources());
        }
        return builder.build();
    }

    private List<SkillRecord> listVisibleRaw(long userId) {
        return skills.listVisibleFull(userId).stream()
                .map(this::withResources)
                .toList();
    }

    private SkillRecord withResources(SkillBean row) {
        Map<String, String> files = new LinkedHashMap<>();
        for (SkillResourceBean resource : resources.findContents(row.getId())) {
            files.put(resource.getResourcePath(), resource.getResourceContent());
        }
        return toRecord(row, files);
    }

    private SkillRecord toRecord(SkillBean row) {
        if (row == null) {
            return null;
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (SkillResourceBean resource : resources.findContents(row.getId())) {
            files.put(resource.getResourcePath(), resource.getResourceContent());
        }
        return toRecord(row, files);
    }

    private SkillRecord toRecord(SkillBean row, Map<String, String> files) {
        if (row == null) {
            return null;
        }
        long owner = row.getOwnerUserId() == null ? PLATFORM_OWNER : row.getOwnerUserId();
        return new SkillRecord(
                row.getId(),
                row.getName(),
                row.getDescription(),
                row.getSkillContent(),
                readMetadata(row.getMetadataJson()),
                row.getSource(),
                owner,
                row.getCreatedAt(),
                row.getUpdatedAt(),
                Map.copyOf(files));
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(metadata);
        } catch (Exception error) {
            throw new IllegalStateException("技能 metadata 序列化失败", error);
        }
    }

    private Map<String, Object> readMetadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception error) {
            // 历史数据可能不是合法 JSON：不影响加载，只是丢失 metadata
            return Map.of();
        }
    }
}
