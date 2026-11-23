package com.beyondzy.demo.service.skill.impl;

import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.domain.skill.SkillResourceBean;
import com.beyondzy.demo.domain.skill.AgentSkillBean;
import com.beyondzy.demo.enums.skill.SkillVisibleScope;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.AgentSkillMapper;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.service.skill.SkillAdminService;
import com.beyondzy.demo.service.skill.SkillAdminView;
import com.beyondzy.demo.service.skill.SkillDocuments;
import com.beyondzy.demo.service.skill.SkillDraft;
import com.beyondzy.demo.service.skill.SkillVisibilityService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理端是技能表的唯一写入方。保存走更新语句，避免仓库覆盖保存时删行丢掉业务列。 */
@Service
public class SkillAdminServiceImpl implements SkillAdminService {
    static final String AGENT_TITLE = "海卓大脑办公助手";

    private final SkillMapper skills;
    private final SkillResourceMapper resources;
    private final AgentSkillMapper bindings;
    private final SkillVisibilityService visibility;

    public SkillAdminServiceImpl(
            SkillMapper skills,
            SkillResourceMapper resources,
            AgentSkillMapper bindings,
            SkillVisibilityService visibility) {
        this.skills = skills;
        this.resources = resources;
        this.bindings = bindings;
        this.visibility = visibility;
    }

    @Override
    public SkillAdminView.Page list(String query, String state, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new SkillException(400, "分页参数不合法");
        String status = statusFilter(state);
        String like = likeQuery(query);
        List<SkillAdminView.Item> items = skills.adminPage(like, status, size, (long) (page - 1) * size).stream()
                .map(SkillAdminServiceImpl::item)
                .toList();
        return new SkillAdminView.Page(items, skills.adminCount(like, status), page, size);
    }

    @Override
    public SkillAdminView.Detail detail(String name) {
        return toDetail(require(name));
    }

    @Override
    @Transactional
    public SkillAdminView.Detail create(long operatorId, SkillDraft draft) {
        if (draft == null) throw new SkillException(400, "请求体不能为空");
        String name = requiredName(draft.name());
        if (skills.findGoverned(name) != null) throw new SkillException(409, "技能已存在: " + name);
        String description = requiredDescription(draft.description());
        Placement placement = placement(operatorId, draft.visibleScope(), draft.ownerDeptId(), draft.status());
        List<StoredFile> files = filesOf(draft.files());
        String markdown = SkillDocuments.compose(name, description, draft.content());
        SkillBean row = newRow(name, description, markdown, placement, operatorId);
        insert(row);
        writeResources(row.getId(), files);
        return detail(name);
    }

    @Override
    @Transactional
    public SkillAdminView.Detail update(long operatorId, String name, SkillDraft draft) {
        SkillBean existing = require(name);
        if (draft == null) throw new SkillException(400, "请求体不能为空");
        String description = requiredDescription(draft.description());
        Placement placement = placement(operatorId, draft.visibleScope(), draft.ownerDeptId(), draft.status());
        List<StoredFile> files = filesOf(draft.files());
        existing.setDescription(description);
        existing.setSkillContent(SkillDocuments.compose(existing.getName(), description, draft.content()));
        existing.setMetadataJson(metadata(existing.getName(), description));
        existing.setStatus(placement.status());
        existing.setVisibleScope(placement.scope().code());
        existing.setOwnerDeptId(placement.departmentId());
        skills.updateGovernance(existing);
        replaceResources(existing.getId(), files);
        return detail(existing.getName());
    }

    @Override
    @Transactional
    public void setEnabled(String name, boolean enabled) {
        require(name);
        skills.updateStatus(name, enabled ? "0" : "1");
    }

    @Override
    @Transactional
    public void delete(String name) {
        SkillBean row = require(name);
        if (bindings.countBySkill(row.getId()) > 0) {
            throw new SkillException(409, "技能仍被绑定，请先解绑");
        }
        skills.deleteById(row.getId());
    }

    @Override
    @Transactional
    public SkillAdminView.Detail importDocument(
            long operatorId, String filename, byte[] body, String visibleScope, Long ownerDeptId, String status) {
        SkillDocuments.Parsed parsed = zip(filename, body) ? SkillDocuments.archive(body) : SkillDocuments.markdown(body);
        if (skills.findGoverned(parsed.name()) != null) throw new SkillException(409, "技能已存在: " + parsed.name());
        Placement placement = placement(operatorId, visibleScope, ownerDeptId, status);
        List<StoredFile> files = new ArrayList<>();
        for (Map.Entry<String, String> entry : parsed.resources().entrySet()) {
            files.add(new StoredFile(entry.getKey(), entry.getValue()));
        }
        SkillBean row = newRow(parsed.name(), parsed.description(), parsed.skillContent(), placement, operatorId);
        insert(row);
        writeResources(row.getId(), files);
        return detail(parsed.name());
    }

    @Override
    public List<SkillAdminView.Option> options(long operatorId) {
        List<SkillAdminView.Option> options = new ArrayList<>();
        for (SkillBean skill : skills.listEnabled()) {
            if (!visibility.visibleTo(skill, operatorId)) continue;
            options.add(new SkillAdminView.Option(skill.getName(), skill.getDescription()));
        }
        return List.copyOf(options);
    }

    @Override
    public SkillAdminView.Agent agent(String agentId) {
        requireAgent(agentId);
        List<SkillAdminView.BoundSkill> skillsBound = bindings.listBound(agentId).stream()
                .map(row -> new SkillAdminView.BoundSkill(
                        row.getName(), row.getDescription(), row.getStatus(), row.getVisibleScope()))
                .toList();
        return new SkillAdminView.Agent(SkillJdbc.SESSION_AGENT, AGENT_TITLE, skillsBound);
    }

    @Override
    @Transactional
    public void replaceBindings(long operatorId, String agentId, List<String> names) {
        requireAgent(agentId);
        if (names == null) throw new SkillException(400, "技能名单不能为空");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : names) unique.add(requiredName(name));
        List<SkillBean> accepted = new ArrayList<>();
        for (String name : unique) {
            SkillBean skill = skills.findGoverned(name);
            if (skill == null) throw new SkillException(404, "技能不存在: " + name);
            if (!"0".equals(skill.getStatus())) throw new SkillException(400, "不能绑定已停用的技能: " + name);
            if (!visibility.visibleTo(skill, operatorId)) {
                throw new SkillException(400, "不能绑定当前账号不可见的技能: " + name);
            }
            accepted.add(skill);
        }
        bindings.deleteByAgent(agentId);
        for (SkillBean skill : accepted) {
            AgentSkillBean row = new AgentSkillBean();
            row.setAgentId(agentId);
            row.setSkillId(skill.getId());
            row.setCreatedBy(operatorId);
            bindings.insert(row);
        }
    }

    private void insert(SkillBean row) {
        try {
            skills.insertGovernance(row);
        } catch (RuntimeException error) {
            if (duplicate(error)) throw new SkillException(409, "技能已存在: " + row.getName());
            throw error;
        }
    }

    private void writeResources(Long skillId, List<StoredFile> files) {
        if (skillId == null) throw new SkillException(500, "技能主键没有生成");
        for (StoredFile file : files) {
            resources.insertResource(skillId, file.path(), file.content());
        }
    }

    private void replaceResources(Long skillId, List<StoredFile> files) {
        resources.deleteBySkillId(skillId);
        writeResources(skillId, files);
    }

    private Placement placement(long operatorId, String visibleScope, Long ownerDeptId, String status) {
        SkillVisibleScope scope = SkillVisibleScope.parse(visibleScope);
        Long departmentId = null;
        if (scope == SkillVisibleScope.DEPARTMENT) {
            departmentId = ownerDeptId != null ? ownerDeptId : visibility.departmentIdOf(operatorId);
            if (departmentId == null) throw new SkillException(400, "本部门可见需要部门，当前账号没有可用部门");
            if (!visibility.departmentActive(departmentId)) throw new SkillException(400, "部门不存在或已停用");
        }
        return new Placement(scope, departmentId, statusCode(status));
    }

    private static String statusCode(String status) {
        if (status == null || status.isBlank() || "0".equals(status)) return "0";
        if ("1".equals(status)) return "1";
        throw new SkillException(400, "状态不合法");
    }

    private static String statusFilter(String state) {
        if (state == null || state.isBlank() || "all".equals(state)) return null;
        if ("enabled".equals(state)) return "0";
        if ("disabled".equals(state)) return "1";
        throw new SkillException(400, "状态筛选不合法");
    }

    private static String requiredName(String name) {
        if (name == null || !SkillDocuments.NAME.matcher(name).matches()) throw new SkillException(400, "技能名不合法");
        return name;
    }

    private static String requiredDescription(String description) {
        if (description == null || description.isBlank()) throw new SkillException(400, "用途说明不能为空");
        return description.trim();
    }

    /** 先校验完全部路径，再落库，避免非法路径留下半截资料。 */
    private static List<StoredFile> filesOf(List<SkillDraft.SkillFile> files) {
        if (files.size() > SkillDocuments.MAX_FILES) throw new SkillException(400, "技能文件超过 50 个");
        Map<String, String> unique = new LinkedHashMap<>();
        for (SkillDraft.SkillFile file : files) {
            if (file == null || file.content() == null) throw new SkillException(400, "参考资料内容为空");
            String path = SkillDocuments.resourcePath(file.path());
            if (file.content().getBytes(StandardCharsets.UTF_8).length > SkillDocuments.RESOURCE_LIMIT) {
                throw new SkillException(400, "资源超过 512KB: " + path);
            }
            if (unique.containsKey(path)) throw new SkillException(400, "重复的文件: " + path);
            unique.put(path, file.content());
        }
        List<StoredFile> stored = new ArrayList<>();
        unique.forEach((path, content) -> stored.add(new StoredFile(path, content)));
        return List.copyOf(stored);
    }

    private static SkillBean newRow(
            String name, String description, String skillContent, Placement placement, long operatorId) {
        SkillBean row = new SkillBean();
        row.setName(name);
        row.setDescription(description);
        row.setSkillContent(skillContent);
        row.setSource(SkillJdbc.SOURCE);
        row.setMetadataJson(metadata(name, description));
        row.setStatus(placement.status());
        row.setVisibleScope(placement.scope().code());
        row.setOwnerDeptId(placement.departmentId());
        row.setCreateBy(operatorId);
        return row;
    }

    private SkillBean require(String name) {
        if (name == null || !SkillDocuments.NAME.matcher(name).matches()) throw new SkillException(400, "技能名不合法");
        SkillBean row = skills.findGoverned(name);
        if (row == null) throw new SkillException(404, "技能不存在: " + name);
        return row;
    }

    private static void requireAgent(String agentId) {
        if (!SkillJdbc.SESSION_AGENT.equals(agentId)) throw new SkillException(404, "智能体不存在: " + agentId);
    }

    private SkillAdminView.Detail toDetail(SkillBean row) {
        List<SkillAdminView.File> files = row.getId() == null
                ? List.of()
                : resources.findContents(row.getId()).stream().map(SkillAdminServiceImpl::file).toList();
        return new SkillAdminView.Detail(
                row.getName(),
                row.getDescription(),
                SkillDocuments.body(row.getSkillContent()),
                row.getStatus(),
                row.getVisibleScope(),
                row.getOwnerDeptId(),
                row.getOwnerDeptName(),
                bound(row),
                row.getUpdatedAt(),
                files);
    }

    private static SkillAdminView.Item item(SkillBean row) {
        int count = row.getResourceCount() == null ? 0 : row.getResourceCount();
        return new SkillAdminView.Item(
                row.getName(),
                row.getDescription(),
                row.getStatus(),
                row.getVisibleScope(),
                row.getOwnerDeptId(),
                row.getOwnerDeptName(),
                count,
                bound(row),
                row.getUpdatedAt());
    }

    private static SkillAdminView.File file(SkillResourceBean row) {
        return new SkillAdminView.File(row.getResourcePath(), row.getResourceContent());
    }

    private static boolean bound(SkillBean row) {
        return row.getBound() != null && row.getBound() > 0;
    }

    /** 转义符与 SQL 的 ESCAPE '!' 一致，先处理 ! 再处理 % 和 _。 */
    private static String likeQuery(String query) {
        if (query == null || query.isBlank()) return null;
        return query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static boolean zip(String filename, byte[] body) {
        if (filename != null && filename.toLowerCase().endsWith(".zip")) return true;
        return body != null && body.length >= 2 && body[0] == 'P' && body[1] == 'K';
    }

    private static boolean duplicate(Throwable error) {
        while (error != null) {
            String message = error.getMessage();
            if (message != null && (message.contains("Unique") || message.contains("UNIQUE")
                    || message.contains("Duplicate") || message.contains("duplicate"))) {
                return true;
            }
            error = error.getCause();
        }
        return false;
    }

    private static String metadata(String name, String description) {
        return "{\"name\":" + json(name) + ",\"description\":" + json(description) + "}";
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\', '"' -> out.append('\\').append(c);
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private record Placement(SkillVisibleScope scope, Long departmentId, String status) {}

    private record StoredFile(String path, String content) {}
}
