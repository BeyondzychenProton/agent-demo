package com.beyondzy.demo.service.skill.impl;

import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.domain.skill.SkillVersionBean;
import com.beyondzy.demo.domain.skill.SkillVersionDependencyBean;
import com.beyondzy.demo.domain.skill.SkillVersionFileBean;
import com.beyondzy.demo.enums.skill.SkillFileKind;
import com.beyondzy.demo.enums.skill.SkillMappingState;
import com.beyondzy.demo.enums.skill.SkillTargetKind;
import com.beyondzy.demo.enums.skill.SkillVersionState;
import com.beyondzy.demo.enums.skill.SkillVisibleScope;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionDependencyMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionFileMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionMapper;
import com.beyondzy.demo.mapper.skill.SkillReleaseMapper;
import com.beyondzy.demo.service.skill.SkillDocuments;
import com.beyondzy.demo.service.skill.SkillImportService;
import com.beyondzy.demo.service.skill.SkillImportView;
import com.beyondzy.demo.service.skill.SkillJson;
import com.beyondzy.demo.service.skill.SkillPackageInspector;
import com.beyondzy.demo.service.skill.SkillResourceCatalog;
import com.beyondzy.demo.service.skill.SkillSourceRules;
import com.beyondzy.demo.service.skill.SkillSupport;
import com.beyondzy.demo.service.skill.SkillTimes;
import com.beyondzy.demo.service.skill.SkillVersionService;
import com.beyondzy.demo.service.skill.SkillVersionView;
import com.beyondzy.demo.service.skill.SkillVisibilityService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 草稿可以改。发布在同一事务里更新运行正文和资料，已发布版本不能被旧请求退回。 */
@Service
public class SkillVersionServiceImpl implements SkillVersionService {
    private static final Pattern PLATFORM = Pattern.compile("^(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)$");

    private final SkillVersionMapper versions;
    private final SkillVersionFileMapper files;
    private final SkillVersionDependencyMapper dependencies;
    private final SkillMapper skills;
    private final SkillResourceMapper resources;
    private final SkillVisibilityService visibility;
    private final SkillResourceCatalog catalog;
    private final TransactionTemplate transactions;
    private final SkillReleaseMapper releases;
    private final SkillBaselineMigrator baselines;

    public SkillVersionServiceImpl(
            SkillVersionMapper versions,
            SkillVersionFileMapper files,
            SkillVersionDependencyMapper dependencies,
            SkillMapper skills,
            SkillResourceMapper resources,
            SkillVisibilityService visibility,
            SkillResourceCatalog catalog,
            SkillReleaseMapper releases,
            SkillBaselineMigrator baselines,
            @Nullable PlatformTransactionManager transactions) {
        this.versions = versions;
        this.files = files;
        this.dependencies = dependencies;
        this.skills = skills;
        this.resources = resources;
        this.visibility = visibility;
        this.catalog = catalog;
        this.releases = releases;
        this.baselines = baselines;
        this.transactions = transactions == null ? null : new TransactionTemplate(transactions);
    }

    @Override
    @Transactional
    public SkillVersionView create(long operatorId, CreateRequest request) {
        if (request == null || request.skillName() == null || !SkillDocuments.NAME.matcher(request.skillName()).matches()) {
            throw new SkillException(400, "INVALID_NAME", "技能名不合法");
        }
        if (request.baseVersionId() != null && !request.baseVersionId().isBlank()) {
            return copyPublished(operatorId, request.skillName(), SkillSupport.positiveId(
                    request.baseVersionId(), "VERSION_NOT_FOUND", "版本不存在"));
        }
        if (request.description() == null || request.description().isBlank()) {
            throw new SkillException(400, "INVALID_ARGUMENT", "用途说明不能为空");
        }
        SkillVisibleScope scope = requiredScope(request.visibleScope());
        Long department = department(scope, request.ownerDeptId());
        String description = request.description().trim();
        String markdown = SkillDocuments.compose(request.skillName(), description, request.content());
        SkillBean runtime = skills.findGoverned(request.skillName());
        long owner = operatorId;
        Long skillId = null;
        if (runtime != null) {
            skillId = runtime.getId();
            if (runtime.getCreateBy() != null) owner = runtime.getCreateBy();
        }
        LocalDateTime now = SkillTimes.now();
        SkillVersionBean version = blank(request.skillName(), skillId, null, description, markdown, scope.code(),
                department, owner, null, now);
        version.setSourceRulesJson(SkillJson.write(new SkillSourceRules()));
        insertVersion(version);
        insertFile(version.getId(), "SKILL.md", SkillFileKind.SKILL, markdown.getBytes(StandardCharsets.UTF_8), now);
        for (TextFile file : request.files() == null ? List.<TextFile>of() : request.files()) {
            Stored stored = textFile(file.path(), file.content());
            insertFile(version.getId(), stored.path(), stored.kind(), stored.body(), now);
        }
        return get(version.getId());
    }

    @Override
    public SkillVersionView.Page list(String skillName, String state, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new SkillException(400, "INVALID_ARGUMENT", "分页参数不合法");
        if (skillName != null && !skillName.isBlank() && !SkillDocuments.NAME.matcher(skillName).matches()) {
            throw new SkillException(400, "INVALID_NAME", "技能名不合法");
        }
        String name = skillName == null || skillName.isBlank() ? null : skillName;
        String filter = state == null || state.isBlank() ? null : SkillVersionState.parse(state).name();
        List<SkillVersionView.Summary> items = versions.page(name, filter, size, (long) (page - 1) * size).stream()
                .map(row -> new SkillVersionView.Summary(
                        SkillSupport.id(row.getId()),
                        row.getSkillName(),
                        row.getVersionNo() == null ? null : Integer.toString(row.getVersionNo()),
                        row.getPackageVersion(),
                        row.getPlatformVersion(),
                        row.getState(),
                        SkillTimes.format(row.getPublishedAt()),
                        SkillSupport.id(row.getRevision())))
                .toList();
        return new SkillVersionView.Page(items, versions.count(name, filter), page, size);
    }

    @Override
    public SkillVersionView get(long id) {
        return detail(require(id), files.listMeta(id), dependencies.listByVersion(id));
    }

    @Override
    @Transactional
    public SkillVersionView update(long operatorId, long id, UpdateRequest request) {
        if (request == null) throw new SkillException(400, "INVALID_ARGUMENT", "请求体不能为空");
        SkillVersionBean version = require(id);
        expect(version, request.revision());
        if (!SkillVersionState.DRAFT.name().equals(version.getState())) {
            throw new SkillException(409, "VERSION_ALREADY_PUBLISHED", "已发布版本不能修改，请另建草稿");
        }
        if (request.description() == null || request.description().isBlank()) {
            throw new SkillException(400, "INVALID_ARGUMENT", "用途说明不能为空");
        }
        SkillVisibleScope scope = requiredScope(request.visibleScope());
        Long department = department(scope, request.ownerDeptId());
        String description = request.description().trim();
        if (request.displayName() != null) {
            String label = request.displayName().trim();
            if (label.length() > 128) throw new SkillException(400, "INVALID_ARGUMENT", "展示名称不能超过 128 个字符");
            version.setDisplayName(label.isBlank() ? null : label);
        }
        String markdown = SkillDocuments.compose(version.getSkillName(), description, request.content());
        List<SkillVersionFileBean> nextFiles = mergeFiles(version.getId(), request.files());
        List<SkillVersionDependencyBean> nextDeps = mergeDependencies(version, request.dependencies());
        version.setDescription(description);
        version.setSkillContent(markdown);
        version.setMetadataJson(SkillSupport.metadata(version.getSkillName(), description));
        version.setVisibleScope(scope.code());
        version.setOwnerDeptId(department);
        version.setGmtModified(SkillTimes.now());
        if (versions.updateDraft(version) != 1) throw conflict(id);
        files.deleteByVersion(id);
        dependencies.deleteByVersion(id);
        LocalDateTime now = SkillTimes.now();
        for (SkillVersionFileBean file : nextFiles) {
            file.setId(null);
            file.setVersionId(id);
            file.setGmtCreate(now);
            file.setGmtModified(now);
            files.insertFile(file);
        }
        for (SkillVersionDependencyBean dependency : nextDeps) {
            dependency.setId(null);
            dependency.setVersionId(id);
            dependency.setGmtCreate(now);
            dependency.setGmtModified(now);
            dependencies.insertDependency(dependency);
        }
        return get(id);
    }

    @Override
    public SkillVersionView.FileBody file(long versionId, long fileId, boolean download) {
        require(versionId);
        SkillVersionFileBean file = files.find(fileId);
        if (file == null || file.getVersionId() == null || file.getVersionId() != versionId) {
            throw new SkillException(404, "FILE_NOT_FOUND", "文件不存在");
        }
        boolean text = SkillSupport.utf8(file.getContent());
        String filename = file.getResourcePath();
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) filename = filename.substring(slash + 1);
        return new SkillVersionView.FileBody(filename, file.getContent(), text, download || !text);
    }

    @Override
    public SkillVersionView.Validation validate(long id, String revision) {
        SkillVersionBean version = require(id);
        expect(version, revision);
        List<SkillVersionDependencyBean> deps = dependencies.listByVersion(id);
        SkillResourceCatalog.CatalogView tools = catalog.capture(needsMcp(deps));
        return evaluate(version, deps, tools, List.of()).validation();
    }

    @Override
    public SkillVersionView.Published publish(long operatorId, long id, PublishRequest request) {
        if (request == null) throw new SkillException(400, "INVALID_ARGUMENT", "请求体不能为空");
        SkillVersionBean version = require(id);
        if (SkillVersionState.PUBLISHED.name().equals(version.getState())) return already(version, request.platformVersion());
        expect(version, request.revision());
        String platformVersion = platformVersion(request.platformVersion());
        SkillVersionBean occupied = versions.findByPlatform(version.getSkillName(), platformVersion);
        if (occupied != null && !occupied.getId().equals(version.getId())) {
            throw new SkillException(409, "VERSION_CONFLICT", "平台版本号已存在");
        }
        List<SkillVersionDependencyBean> deps = dependencies.listByVersion(id);
        boolean mcp = needsMcp(deps);
        SkillResourceCatalog.CatalogView first = catalog.capture(mcp);
        Outcome outcome = evaluate(version, deps, first, request.acknowledgedRuleKeys());
        if (!outcome.validation().canPublish()) {
            throw new SkillException(422, "CHECK_BLOCKED", "不满足发布条件",
                    Map.of("blockers", outcome.validation().blockers()));
        }
        SkillResourceCatalog.CatalogView second = catalog.capture(mcp);
        if (!first.fingerprint().equals(second.fingerprint())) {
            throw new SkillException(503, "RESOURCE_CATALOG_UNAVAILABLE", "工具目录刚发生变化，请重试");
        }
        Runnable work = () -> publishLocked(operatorId, id, SkillSupport.revision(request.revision()), platformVersion);
        if (transactions == null) work.run();
        else transactions.executeWithoutResult(status -> work.run());
        SkillVersionBean published = versions.find(id);
        SkillBean runtime = skills.findGoverned(published.getSkillName());
        return new SkillVersionView.Published(
                SkillSupport.id(published.getId()),
                published.getSkillName(),
                published.getState(),
                published.getPlatformVersion(),
                SkillTimes.format(published.getPublishedAt()),
                runtime == null ? "1" : runtime.getStatus());
    }

    private void publishLocked(long operatorId, long id, long revision, String platformVersion) {
        SkillVersionBean version = versions.find(id);
        if (version == null) throw new SkillException(404, "VERSION_NOT_FOUND", "版本不存在");
        versions.lockBySkillName(version.getSkillName());
        version = versions.lock(id);
        if (SkillVersionState.PUBLISHED.name().equals(version.getState())) return;
        if (version.getRevision() == null || version.getRevision() != revision) {
            throw SkillSupport.conflict(version.getRevision() == null ? 0 : version.getRevision());
        }
        SkillBean locked = skills.lockByName(version.getSkillName());
        long skillId;
        String status;
        if (locked == null) {
            SkillBean created = new SkillBean();
            created.setName(version.getSkillName());
            created.setDescription(version.getDescription());
            created.setSkillContent(version.getSkillContent());
            created.setSource(SkillJdbc.SOURCE);
            created.setMetadataJson(SkillSupport.metadata(version.getSkillName(), version.getDescription()));
            created.setStatus("1");
            created.setVisibleScope(version.getVisibleScope());
            created.setOwnerDeptId(version.getOwnerDeptId());
            created.setCreateBy(version.getCreatedBy());
            try {
                skills.insertGovernance(created);
            } catch (RuntimeException error) {
                if (SkillSupport.duplicate(error)) throw new SkillException(409, "VERSION_CONFLICT", "技能已存在，请重试");
                throw error;
            }
            skillId = created.getId();
            status = "1";
        } else {
            baselines.ensure(locked.getName(), operatorId);
            if (version.getSkillId() != null && !version.getSkillId().equals(locked.getId())) {
                throw new SkillException(409, "VERSION_CONFLICT", "版本指向了另一条技能");
            }
            locked.setDescription(version.getDescription());
            locked.setSkillContent(version.getSkillContent());
            locked.setMetadataJson(SkillSupport.metadata(version.getSkillName(), version.getDescription()));
            locked.setVisibleScope(version.getVisibleScope());
            locked.setOwnerDeptId(version.getOwnerDeptId());
            skills.updatePublishedContent(locked);
            resources.deleteBySkillId(locked.getId());
            skillId = locked.getId();
            status = locked.getStatus();
        }
        project(skillId, files.listContent(id));
        LocalDateTime now = SkillTimes.now();
        version.setSkillId(skillId);
        version.setPlatformVersion(platformVersion);
        version.setPublishedBy(operatorId);
        version.setPublishedAt(now);
        version.setGmtModified(now);
        if (versions.publish(version) != 1) throw conflict(id);
        releases.point(skillId, id);
        if (status == null) status = "1";
    }

    private void project(long skillId, List<SkillVersionFileBean> stored) {
        for (SkillVersionFileBean file : stored) {
            if (!"REFERENCE".equals(file.getFileKind()) && !"SCRIPT".equals(file.getFileKind())) continue;
            if (!SkillSupport.utf8(file.getContent())) continue;
            String path = SkillDocuments.resourcePath(file.getResourcePath());
            resources.insertResource(skillId, path, new String(file.getContent(), StandardCharsets.UTF_8));
        }
    }

    private SkillVersionView copyPublished(long operatorId, String skillName, long baseId) {
        SkillVersionBean base = require(baseId);
        if (!skillName.equals(base.getSkillName())) throw new SkillException(404, "VERSION_NOT_FOUND", "版本不存在");
        if (!SkillVersionState.PUBLISHED.name().equals(base.getState())) {
            throw new SkillException(422, "CHECK_BLOCKED", "只能从已发布版本复制草稿");
        }
        LocalDateTime now = SkillTimes.now();
        SkillVersionBean version = blank(skillName, base.getSkillId(), base.getPackageVersion(), base.getDescription(),
                base.getSkillContent(), base.getVisibleScope(), base.getOwnerDeptId(), base.getCreatedBy(), null, now);
        version.setSourceRulesJson(base.getSourceRulesJson());
        version.setDisplayName(base.getDisplayName());
        version.setMetadataJson(base.getMetadataJson());
        insertVersion(version);
        for (SkillVersionFileBean file : files.listContent(baseId)) {
            insertFile(version.getId(), file.getResourcePath(), SkillFileKind.valueOf(file.getFileKind()), file.getContent(), now);
        }
        for (SkillVersionDependencyBean dependency : dependencies.listByVersion(baseId)) {
            dependency.setId(null);
            dependency.setVersionId(version.getId());
            dependency.setGmtCreate(now);
            dependency.setGmtModified(now);
            dependencies.insertDependency(dependency);
        }
        return get(version.getId());
    }

    private List<SkillVersionFileBean> mergeFiles(long versionId, List<UpdateFile> requested) {
        List<SkillVersionFileBean> existing = files.listContent(versionId);
        Map<Long, SkillVersionFileBean> byId = new LinkedHashMap<>();
        for (SkillVersionFileBean file : existing) byId.put(file.getId(), file);
        List<SkillVersionFileBean> next = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (UpdateFile update : requested == null ? List.<UpdateFile>of() : requested) {
            if (update == null) throw new SkillException(400, "INVALID_ARGUMENT", "文件不能为空");
            if (update.fileId() == null || update.fileId().isBlank()) {
                if (update.content() == null) throw new SkillException(400, "INVALID_ARGUMENT", "新文件需要内容");
                Stored stored = textFile(update.path(), update.content());
                if (!paths.add(stored.path().toLowerCase())) throw new SkillException(400, "INVALID_ARGUMENT", "重复的文件");
                next.add(bytes(stored.path(), stored.kind().name(), stored.body()));
                continue;
            }
            long fileId = SkillSupport.positiveId(update.fileId(), "FILE_NOT_FOUND", "文件不存在");
            SkillVersionFileBean current = byId.get(fileId);
            if (current == null) throw new SkillException(404, "FILE_NOT_FOUND", "文件不存在");
            if (!seen.add(fileId)) throw new SkillException(400, "INVALID_ARGUMENT", "文件重复");
            if ("SKILL".equals(current.getFileKind())) {
                if (update.content() != null) throw new SkillException(422, "CHECK_BLOCKED", "不能替换原包入口快照");
                if (update.path() != null && !update.path().isBlank() && !current.getResourcePath().equals(update.path())) {
                    throw new SkillException(422, "CHECK_BLOCKED", "不能改原包入口路径");
                }
                paths.add(current.getResourcePath().toLowerCase());
                next.add(current);
                continue;
            }
            if (!SkillSupport.utf8(current.getContent()) && update.content() != null) {
                throw new SkillException(422, "CHECK_BLOCKED", "二进制文件只能保留");
            }
            byte[] body = update.content() == null ? current.getContent() : update.content().getBytes(StandardCharsets.UTF_8);
            if (body.length > SkillDocuments.RESOURCE_LIMIT) throw new SkillException(400, "INVALID_ARGUMENT", "资源超过 512KB");
            String path = update.path() == null || update.path().isBlank() ? current.getResourcePath() : SkillDocuments.resourcePath(update.path());
            if (!paths.add(path.toLowerCase())) throw new SkillException(400, "INVALID_ARGUMENT", "重复的文件");
            next.add(bytes(path, SkillDocuments.kind(path).name(), body));
        }
        for (SkillVersionFileBean file : existing) {
            if (seen.contains(file.getId()) || !"SKILL".equals(file.getFileKind())) continue;
            if (paths.add(file.getResourcePath().toLowerCase())) next.add(file);
        }
        return next;
    }

    private List<SkillVersionDependencyBean> mergeDependencies(
            SkillVersionBean version, List<SkillImportService.DependencyInput> requested) {
        SkillSourceRules rules = rules(version);
        List<SkillPackageInspector.SourceDependency> declared =
                rules.dependencies == null ? List.of() : rules.dependencies;
        List<SkillImportService.DependencyInput> items = requested == null ? List.of() : requested;
        if (declared.isEmpty()) {
            if (!items.isEmpty()) throw new SkillException(422, "CHECK_BLOCKED", "这个草稿没有可映射的原包依赖");
            return List.of();
        }
        Map<String, SkillPackageInspector.SourceDependency> byKey = new LinkedHashMap<>();
        for (SkillPackageInspector.SourceDependency dependency : declared) byKey.put(dependency.sourceKey(), dependency);
        if (items.size() != byKey.size()) throw new SkillException(422, "CHECK_BLOCKED", "必须提交全部依赖");
        Set<String> seen = new HashSet<>();
        List<SkillVersionDependencyBean> rows = new ArrayList<>();
        for (SkillImportService.DependencyInput item : items) {
            if (item == null || !byKey.containsKey(item.sourceKey()) || !seen.add(item.sourceKey())) {
                throw new SkillException(422, "CHECK_BLOCKED", "依赖不属于这个版本");
            }
            rows.add(toDependency(byKey.get(item.sourceKey()), item));
        }
        return rows;
    }

    private SkillVersionDependencyBean toDependency(
            SkillPackageInspector.SourceDependency source, SkillImportService.DependencyInput item) {
        SkillTargetKind kind = SkillTargetKind.parse(item.targetKind());
        SkillMappingState mapping = SkillMappingState.parse(item.mappingState());
        if (mapping != SkillMappingState.CONFIRMED && mapping != SkillMappingState.PENDING) {
            throw new SkillException(422, "CHECK_BLOCKED", "建议映射还需要管理员确认");
        }
        SkillVersionDependencyBean row = new SkillVersionDependencyBean();
        row.setSourceKey(source.sourceKey());
        row.setCapabilityKey(source.capabilityKey());
        row.setRequiredFlag(source.required() ? "1" : "0");
        if (mapping == SkillMappingState.PENDING) {
            if (kind != SkillTargetKind.UNRESOLVED || hasText(item.targetProviderKey()) || hasText(item.targetToolKey())) {
                throw new SkillException(422, "CHECK_BLOCKED", "待接入依赖不能带目标");
            }
            if (!hasText(item.reason())) throw new SkillException(422, "CHECK_BLOCKED", "待接入依赖需要原因");
            row.setTargetKind(kind.name());
            row.setMappingState(mapping.name());
            row.setReason(item.reason().trim());
            return row;
        }
        if (kind == SkillTargetKind.MCP_TOOL && (!hasText(item.targetProviderKey()) || !hasText(item.targetToolKey()))) {
            throw new SkillException(422, "CHECK_BLOCKED", "MCP 工具需要服务和工具标识");
        }
        if (kind == SkillTargetKind.PLATFORM && (hasText(item.targetProviderKey()) || !hasText(item.targetToolKey()))) {
            throw new SkillException(422, "CHECK_BLOCKED", "平台能力只填写能力标识");
        }
        if (!available(kind, item.targetProviderKey(), item.targetToolKey(), source.capabilityKey())) {
            throw new SkillException(422, "DEPENDENCY_UNAVAILABLE", "目标能力不存在或不可用");
        }
        row.setTargetKind(kind.name());
        row.setTargetProviderKey(kind == SkillTargetKind.PLATFORM ? null : item.targetProviderKey());
        row.setTargetToolKey(item.targetToolKey());
        row.setMappingState(SkillMappingState.CONFIRMED.name());
        row.setReason(hasText(item.reason()) ? item.reason().trim() : null);
        return row;
    }

    private Outcome evaluate(
            SkillVersionBean version, List<SkillVersionDependencyBean> deps, SkillResourceCatalog.CatalogView tools,
            List<String> acknowledged) {
        List<SkillImportView.Check> checks = new ArrayList<>();
        List<SkillImportView.Blocker> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (version.getSkillContent() == null
                || version.getSkillContent().getBytes(StandardCharsets.UTF_8).length > SkillDocuments.SKILL_MD_LIMIT) {
            blockers.add(new SkillImportView.Blocker("CHECK_BLOCKED", null, "CONTENT", "拟发布正文超过限制"));
        }
        if (version.getDescription() == null || version.getDescription().isBlank()) {
            blockers.add(new SkillImportView.Blocker("CHECK_BLOCKED", null, "DESCRIPTION", "用途说明不能为空"));
        }
        if ("1".equals(version.getVisibleScope())
                && (version.getOwnerDeptId() == null || !visibility.departmentActive(version.getOwnerDeptId()))) {
            blockers.add(new SkillImportView.Blocker("CHECK_BLOCKED", null, "DEPARTMENT", "部门不存在或已停用"));
        }
        SkillSourceRules rules = rules(version);
        Set<String> acks = new HashSet<>(acknowledged == null ? List.of() : acknowledged);
        if (rules.rules != null) {
            for (SkillPackageInspector.SourceRule rule : rules.rules) {
                boolean error = "ERROR".equals(rule.level());
                checks.add(new SkillImportView.Check(
                        rule.key(), rule.level(), error ? "FAIL" : "WARN", rule.message(), rule.acknowledge()));
                if (error) {
                    blockers.add(new SkillImportView.Blocker("CHECK_BLOCKED", null, rule.key(), rule.message()));
                } else if (rule.acknowledge() && ("publish".equals(rule.blocks()) || "both".equals(rule.blocks()))
                        && !acks.contains(rule.key())) {
                    blockers.add(new SkillImportView.Blocker("CHECK_BLOCKED", null, rule.key(), rule.message()));
                } else if (!error) {
                    warnings.add(rule.message());
                }
            }
        }
        for (SkillVersionDependencyBean dependency : deps) {
            boolean required = !"0".equals(dependency.getRequiredFlag());
            if ("PENDING".equals(dependency.getMappingState())) {
                warnings.add("仍有待接入依赖");
                if (required) {
                    blockers.add(new SkillImportView.Blocker(
                            "DEPENDENCY_UNAVAILABLE", dependency.getSourceKey(), null, "必需依赖尚未接入"));
                }
                continue;
            }
            if (!"CONFIRMED".equals(dependency.getMappingState()) && required) {
                blockers.add(new SkillImportView.Blocker(
                        "MAPPING_NOT_CONFIRMED", dependency.getSourceKey(), null, "依赖尚未确认"));
                continue;
            }
            if ("CONFIRMED".equals(dependency.getMappingState())
                    && !contains(tools, dependency)) {
                blockers.add(new SkillImportView.Blocker(
                        "DEPENDENCY_UNAVAILABLE", dependency.getSourceKey(), null, "目标能力不存在或不可用"));
            }
        }
        boolean canPublish = blockers.isEmpty() && SkillVersionState.DRAFT.name().equals(version.getState());
        if (SkillVersionState.PUBLISHED.name().equals(version.getState())) canPublish = false;
        return new Outcome(new SkillVersionView.Validation(
                SkillSupport.id(version.getId()), SkillSupport.id(version.getRevision()), canPublish, checks, blockers,
                warnings));
    }

    private boolean available(SkillTargetKind kind, String provider, String tool, String capability) {
        return contains(catalog.capture(kind == SkillTargetKind.MCP_TOOL), kind.name(), provider, tool, capability);
    }

    private static boolean contains(SkillResourceCatalog.CatalogView tools, SkillVersionDependencyBean dependency) {
        return contains(tools, dependency.getTargetKind(), dependency.getTargetProviderKey(),
                dependency.getTargetToolKey(), dependency.getCapabilityKey());
    }

    private static boolean contains(
            SkillResourceCatalog.CatalogView tools, String kind, String provider, String tool, String capability) {
        for (SkillResourceCatalog.SkillResourceOption option : tools.options()) {
            if (!option.available() || !option.targetKind().equals(kind) || !option.toolKey().equals(tool)) continue;
            if ("MCP_TOOL".equals(kind) && (provider == null || !provider.equals(option.providerKey()))) continue;
            if (capability != null && option.capabilityKeys() != null && !option.capabilityKeys().isEmpty()
                    && !option.capabilityKeys().contains(capability)) {
                return false;
            }
            return true;
        }
        return false;
    }

    private static boolean needsMcp(List<SkillVersionDependencyBean> deps) {
        for (SkillVersionDependencyBean dependency : deps) {
            if ("CONFIRMED".equals(dependency.getMappingState()) && "MCP_TOOL".equals(dependency.getTargetKind())) {
                return true;
            }
        }
        return false;
    }

    private SkillVersionView.Published already(SkillVersionBean version, String platformVersion) {
        if (platformVersion != null && platformVersion.equals(version.getPlatformVersion())) {
            SkillBean runtime = skills.findGoverned(version.getSkillName());
            return new SkillVersionView.Published(
                    SkillSupport.id(version.getId()),
                    version.getSkillName(),
                    version.getState(),
                    version.getPlatformVersion(),
                    SkillTimes.format(version.getPublishedAt()),
                    runtime == null ? "1" : runtime.getStatus());
        }
        throw new SkillException(409, "VERSION_ALREADY_PUBLISHED", "版本已发布，不能改成另一个平台版本号");
    }

    private SkillVersionView detail(
            SkillVersionBean version, List<SkillVersionFileBean> fileRows, List<SkillVersionDependencyBean> deps) {
        List<SkillVersionView.FileItem> items = new ArrayList<>();
        for (SkillVersionFileBean file : fileRows) {
            items.add(new SkillVersionView.FileItem(
                    SkillSupport.id(file.getId()),
                    file.getResourcePath(),
                    file.getFileKind(),
                    file.getByteSize() == null ? 0 : file.getByteSize(),
                    file.getContentSha256(),
                    true));
        }
        List<SkillImportView.Dependency> mapped = new ArrayList<>();
        for (SkillVersionDependencyBean dependency : deps) {
            mapped.add(new SkillImportView.Dependency(
                    dependency.getSourceKey(),
                    dependency.getCapabilityKey(),
                    !"0".equals(dependency.getRequiredFlag()),
                    null,
                    dependency.getTargetKind(),
                    dependency.getTargetProviderKey(),
                    dependency.getTargetToolKey(),
                    dependency.getMappingState(),
                    dependency.getReason()));
        }
        SkillSourceRules rules = rules(version);
        List<SkillImportView.Check> checks = new ArrayList<>();
        if (rules.rules != null) {
            for (SkillPackageInspector.SourceRule rule : rules.rules) {
                checks.add(new SkillImportView.Check(
                        rule.key(), rule.level(), "ERROR".equals(rule.level()) ? "FAIL" : "WARN", rule.message(),
                        rule.acknowledge()));
            }
        }
        return new SkillVersionView(
                SkillSupport.id(version.getId()),
                SkillSupport.id(version.getRevision()),
                version.getSkillName(),
                SkillSupport.id(version.getSkillId()),
                version.getVersionNo() == null ? null : Integer.toString(version.getVersionNo()),
                version.getPackageVersion(),
                version.getPlatformVersion(),
                version.getState(),
                version.getDescription(),
                SkillDocuments.body(version.getSkillContent()),
                version.getVisibleScope(),
                version.getOwnerDeptId(),
                SkillSupport.id(version.getImportId()),
                SkillTimes.format(version.getPublishedAt()),
                items,
                mapped,
                checks,
                version.getDisplayName());
    }

    private void insertVersion(SkillVersionBean version) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            version.setVersionNo(versions.maxVersionNo(version.getSkillName()) + 1);
            try {
                versions.insertVersion(version);
                return;
            } catch (RuntimeException error) {
                last = error;
                if (!SkillSupport.duplicate(error)) throw error;
            }
        }
        if (last != null) throw new SkillException(409, "VERSION_CONFLICT", "版本序号冲突，请重试");
    }

    private void insertFile(long versionId, String path, SkillFileKind kind, byte[] body, LocalDateTime now) {
        SkillVersionFileBean row = bytes(path, kind.name(), body);
        row.setVersionId(versionId);
        row.setGmtCreate(now);
        row.setGmtModified(now);
        files.insertFile(row);
    }

    private static SkillVersionFileBean bytes(String path, String kind, byte[] body) {
        SkillVersionFileBean row = new SkillVersionFileBean();
        row.setResourcePath(path);
        row.setFileKind(kind);
        row.setContent(body);
        row.setContentSha256(SkillSupport.sha256(body));
        row.setByteSize(body.length);
        return row;
    }

    private static SkillVersionBean blank(
            String name, Long skillId, String packageVersion, String description, String markdown, String scope,
            Long department, long owner, Long importId, LocalDateTime now) {
        SkillVersionBean version = new SkillVersionBean();
        version.setSkillName(name);
        version.setSkillId(skillId);
        version.setPackageVersion(packageVersion);
        version.setState(SkillVersionState.DRAFT.name());
        version.setDescription(description);
        version.setSkillContent(markdown);
        version.setMetadataJson(SkillSupport.metadata(name, description));
        version.setVisibleScope(scope);
        version.setOwnerDeptId(department);
        version.setCreatedBy(owner);
        version.setImportId(importId);
        version.setRevision(0L);
        version.setGmtCreate(now);
        version.setGmtModified(now);
        return version;
    }

    private SkillVersionBean require(long id) {
        SkillVersionBean version = versions.find(id);
        if (version == null) throw new SkillException(404, "VERSION_NOT_FOUND", "版本不存在");
        return version;
    }

    private static void expect(SkillVersionBean version, String revision) {
        long expected = SkillSupport.revision(revision);
        long current = version.getRevision() == null ? 0 : version.getRevision();
        if (current != expected) throw SkillSupport.conflict(current);
    }

    private SkillException conflict(long id) {
        SkillVersionBean current = versions.find(id);
        return SkillSupport.conflict(current == null || current.getRevision() == null ? 0 : current.getRevision());
    }

    private static SkillSourceRules rules(SkillVersionBean version) {
        SkillSourceRules rules = SkillJson.read(version.getSourceRulesJson(), SkillSourceRules.class);
        return rules == null ? new SkillSourceRules() : rules;
    }

    private Long department(SkillVisibleScope scope, Long ownerDeptId) {
        if (scope != SkillVisibleScope.DEPARTMENT) return null;
        if (ownerDeptId == null) throw new SkillException(400, "INVALID_ARGUMENT", "本部门可见需要明确部门");
        if (!visibility.departmentActive(ownerDeptId)) throw new SkillException(400, "INVALID_ARGUMENT", "部门不存在或已停用");
        return ownerDeptId;
    }

    private static SkillVisibleScope requiredScope(String visibleScope) {
        if (visibleScope == null || visibleScope.isBlank()) {
            throw new SkillException(400, "INVALID_ARGUMENT", "可见范围不能为空");
        }
        return SkillVisibleScope.parse(visibleScope);
    }

    private static String platformVersion(String raw) {
        if (raw == null || !PLATFORM.matcher(raw).matches()) {
            throw new SkillException(400, "INVALID_ARGUMENT", "平台版本号需要写成 1.2.3");
        }
        return raw;
    }

    private static Stored textFile(String path, String content) {
        if (content == null) throw new SkillException(400, "INVALID_ARGUMENT", "参考资料内容为空");
        String normalized = SkillDocuments.resourcePath(path);
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        if (body.length > SkillDocuments.RESOURCE_LIMIT) {
            throw new SkillException(400, "INVALID_ARGUMENT", "资源超过 512KB: " + normalized);
        }
        return new Stored(normalized, SkillDocuments.kind(normalized), body);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Stored(String path, SkillFileKind kind, byte[] body) {}

    private record Outcome(SkillVersionView.Validation validation) {}
}
