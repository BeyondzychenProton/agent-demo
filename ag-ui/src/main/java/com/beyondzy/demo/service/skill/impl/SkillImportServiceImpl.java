package com.beyondzy.demo.service.skill.impl;

import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.domain.skill.SkillImportBean;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.beyondzy.demo.contract.PageResult;
import com.beyondzy.demo.contract.Pages;
import com.beyondzy.demo.domain.skill.SkillVersionBean;
import com.beyondzy.demo.domain.skill.SkillVersionDependencyBean;
import com.beyondzy.demo.domain.skill.SkillVersionFileBean;
import com.beyondzy.demo.enums.skill.SkillContentPolicy;
import com.beyondzy.demo.enums.skill.SkillEntryPolicy;
import com.beyondzy.demo.enums.skill.SkillImportState;
import com.beyondzy.demo.enums.skill.SkillMappingState;
import com.beyondzy.demo.enums.skill.SkillVersionState;
import com.beyondzy.demo.enums.skill.SkillTargetKind;
import com.beyondzy.demo.enums.skill.SkillVisibleScope;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillImportMapper;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionDependencyMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionFileMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionMapper;
import com.beyondzy.demo.service.skill.support.SkillCheckDocument;
import com.beyondzy.demo.service.skill.support.SkillDocuments;
import com.beyondzy.demo.service.skill.SkillImportService;
import com.beyondzy.demo.service.skill.view.SkillImportView;
import com.beyondzy.demo.service.skill.support.SkillJson;
import com.beyondzy.demo.service.skill.support.SkillPackageInspector;
import com.beyondzy.demo.service.skill.SkillResourceCatalog;
import com.beyondzy.demo.service.skill.support.SkillSourceRules;
import com.beyondzy.demo.service.skill.support.SkillSupport;
import com.beyondzy.demo.service.skill.support.SkillTimes;
import com.beyondzy.demo.service.skill.SkillVisibilityService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 导入只生成草稿。运行表要等版本发布才更新。 */
@Service
public class SkillImportServiceImpl implements SkillImportService {
    private static final int OPEN_HOURS = 24;

    private final SkillImportMapper imports;
    private final SkillMapper skills;
    private final SkillVersionMapper versions;
    private final SkillVersionFileMapper files;
    private final SkillVersionDependencyMapper dependencies;
    private final SkillVisibilityService visibility;
    private final SkillResourceCatalog catalog;
    private final TransactionTemplate transactions;

    public SkillImportServiceImpl(
            SkillImportMapper imports,
            SkillMapper skills,
            SkillVersionMapper versions,
            SkillVersionFileMapper files,
            SkillVersionDependencyMapper dependencies,
            SkillVisibilityService visibility,
            SkillResourceCatalog catalog,
            @Nullable PlatformTransactionManager transactions) {
        this.imports = imports;
        this.skills = skills;
        this.versions = versions;
        this.files = files;
        this.dependencies = dependencies;
        this.visibility = visibility;
        this.catalog = catalog;
        this.transactions = transactions == null ? null : new TransactionTemplate(transactions);
    }

    @Override
    public SkillImportView.Created upload(long operatorId, String requestKey, String filename, byte[] body) {
        String key = requestKey(requestKey);
        String name = filename(filename);
        if (body == null || body.length == 0) fail(400, "INVALID_ARGUMENT", "文件为空");
        boolean markdown = SkillPackageInspector.markdownName(name);
        boolean zip = SkillPackageInspector.zipName(name);
        if (!markdown && !zip) fail(415, "UNSUPPORTED_MEDIA_TYPE", "只接受 Markdown 或 ZIP");
        if (markdown && body.length > SkillDocuments.SKILL_MD_LIMIT) fail(413, "UPLOAD_TOO_LARGE", "Markdown 超过 256KB");
        if (zip && body.length > SkillDocuments.ARCHIVE_LIMIT) fail(413, "UPLOAD_TOO_LARGE", "压缩包超过 2MB");
        String sha = SkillSupport.sha256(body);
        SkillImportBean existing = imports.findByRequest(operatorId, key);
        if (existing != null) {
            if (sha.equals(existing.getPackageSha256()) && name.equals(existing.getFilename())) {
                return new SkillImportView.Created(view(open(operatorId, existing)), false);
            }
            fail(409, "IDEMPOTENCY_CONFLICT", "相同请求键对应了不同的上传内容");
        }
        SkillPackageInspector.Inspection inspection = SkillPackageInspector.inspect(name, body);
        LocalDateTime now = SkillTimes.now();
        SkillImportBean row = new SkillImportBean();
        row.setRequestKey(key);
        row.setCreatedBy(operatorId);
        row.setFilename(name);
        row.setPackageSha256(sha);
        row.setPackageBody(body);
        row.setCheckerVersion(SkillPackageInspector.VERSION);
        row.setRevision(0L);
        row.setExpiresAt(now.plusHours(OPEN_HOURS));
        row.setGmtCreate(now);
        row.setGmtModified(now);
        SkillCheckDocument document = baseDocument(inspection);
        row.setCheckReportJson(SkillJson.write(document));
        if (inspection.structural()) {
            row.setState(SkillImportState.FAILED.name());
            row.setErrorCode(inspection.errorCode());
        } else {
            row.setState(SkillImportState.UPLOADED.name());
        }
        try {
            imports.insertImport(row);
        } catch (RuntimeException error) {
            if (!SkillSupport.duplicate(error)) throw error;
            SkillImportBean raced = imports.findByRequest(operatorId, key);
            if (raced != null && sha.equals(raced.getPackageSha256()) && name.equals(raced.getFilename())) {
                return new SkillImportView.Created(view(open(operatorId, raced)), false);
            }
            fail(409, "IDEMPOTENCY_CONFLICT", "相同请求键对应了不同的上传内容");
        }
        return new SkillImportView.Created(view(imports.find(row.getId())), true);
    }

    @Override
    public PageResult<SkillImportView.Summary> list(long operatorId, String state, int page, int size) {
        page(page, size);
        String filter = state == null || state.isBlank() ? null : SkillImportState.parse(state).name();
        Page<SkillImportBean> query = new Page<>(page, size, false);
        imports.page(query, operatorId, filter);
        LocalDateTime now = SkillTimes.now();
        List<SkillImportMapper.Due> expiring = new ArrayList<>();
        List<SkillImportView.Summary> items = new ArrayList<>();
        for (SkillImportBean row : query.getRecords()) {
            if (due(row)) {
                expiring.add(new SkillImportMapper.Due(row.getId(), row.getRevision() == null ? 0 : row.getRevision()));
                row.setState(SkillImportState.EXPIRED.name());
            }
            items.add(new SkillImportView.Summary(
                    SkillSupport.id(row.getId()),
                    SkillSupport.id(row.getRevision()),
                    row.getState(),
                    step(row, null),
                    row.getFilename(),
                    row.getPackageSha256(),
                    Objects.equals(row.getSourceAvailable(), SkillImportBean.SOURCE_AVAILABLE),
                    SkillTimes.format(row.getExpiresAt()),
                    row.getTargetName()));
        }
        if (!expiring.isEmpty()) imports.expireAll(expiring, now);
        return new PageResult<>(items, imports.count(operatorId, filter), page, size);
    }

    @Override
    public SkillImportView get(long operatorId, long id) {
        return view(open(operatorId, imports.find(id)));
    }

    @Override
    public SkillImportView configure(
            long operatorId, long id, String revision, String targetName, String entryPolicy, String contentPolicy,
            String visibleScope, Long ownerDeptId) {
        SkillImportBean row = open(operatorId, imports.find(id));
        expect(row, revision);
        if (SkillImportState.CONFIRMED.name().equals(row.getState())) {
            fail(409, "IMPORT_ALREADY_CONFIRMED", "导入已确认，不能再改策略");
        }
        byte[] body = packageBody(id, operatorId);
        SkillPackageInspector.Inspection inspection = SkillPackageInspector.inspect(row.getFilename(), body);
        if (inspection.structural()) fail(422, "CHECK_BLOCKED", "包结构损坏，请重新上传");
        String target = name(targetName);
        SkillEntryPolicy entry = SkillEntryPolicy.parse(entryPolicy);
        SkillContentPolicy content = SkillContentPolicy.parse(contentPolicy);
        SkillPackageInspector.propose(inspection, target, entry, content);
        SkillVisibleScope scope = scope(visibleScope);
        Long department = department(operatorId, scope, ownerDeptId);
        boolean catalogFailed = false;
        SkillResourceCatalog.CatalogView tools;
        try {
            tools = catalog.capture(true);
        } catch (SkillException error) {
            if (error.status() != 503) throw error;
            catalogFailed = true;
            tools = catalog.capture(false);
        }
        boolean exists = skills.findGoverned(target) != null;
        SkillCheckDocument document = reviewed(inspection, scope.code(), department, tools, catalogFailed, exists);
        row.setState(SkillImportState.CHECKED.name());
        row.setTargetName(target);
        row.setEntryPolicy(entry.name());
        row.setContentPolicy(content.name());
        row.setCheckReportJson(SkillJson.write(document));
        row.setCheckerVersion(SkillPackageInspector.VERSION);
        row.setErrorCode(null);
        row.setGmtModified(SkillTimes.now());
        if (imports.updateReport(row) != 1) throw currentConflict(id);
        return get(operatorId, id);
    }

    @Override
    public SkillImportView saveDependencies(long operatorId, long id, String revision, List<DependencyInput> items) {
        SkillImportBean row = open(operatorId, imports.find(id));
        expect(row, revision);
        if (!SkillImportState.CHECKED.name().equals(row.getState())) {
            fail(422, "CHECK_BLOCKED", "请先保存检查策略");
        }
        SkillCheckDocument document = document(row);
        if (items == null) fail(400, "INVALID_ARGUMENT", "依赖列表不能为空");
        Map<String, SkillCheckDocument.DependencyDraft> current = new LinkedHashMap<>();
        for (SkillCheckDocument.DependencyDraft draft : document.dependencyDrafts) current.put(draft.sourceKey, draft);
        if (items.size() != current.size()) fail(422, "CHECK_BLOCKED", "必须提交全部依赖，不能删掉必需项");
        Set<String> seen = new HashSet<>();
        List<SkillCheckDocument.DependencyDraft> next = new ArrayList<>();
        for (DependencyInput item : items) {
            if (item == null || item.sourceKey() == null || !current.containsKey(item.sourceKey())) {
                fail(422, "CHECK_BLOCKED", "依赖不属于这个包");
            }
            if (!seen.add(item.sourceKey())) fail(422, "CHECK_BLOCKED", "依赖标识重复");
            next.add(apply(current.get(item.sourceKey()), item));
        }
        document.dependencyDrafts = next;
        document.confirmations = new ArrayList<>();
        row.setCheckReportJson(SkillJson.write(document));
        row.setErrorCode(null);
        row.setGmtModified(SkillTimes.now());
        if (imports.updateReport(row) != 1) throw currentConflict(id);
        return get(operatorId, id);
    }

    @Override
    public SkillImportView confirm(
            long operatorId, long id, String revision, boolean acknowledgeExisting, List<String> acknowledgedRuleKeys) {
        SkillImportBean loaded = open(operatorId, imports.find(id));
        if (SkillImportState.CONFIRMED.name().equals(loaded.getState()) && loaded.getResultVersionId() != null) {
            return view(loaded);
        }
        expect(loaded, revision);
        Runnable work = () -> confirmLocked(operatorId, id, SkillSupport.revision(revision), acknowledgeExisting,
                acknowledgedRuleKeys == null ? List.of() : acknowledgedRuleKeys);
        if (transactions == null) work.run();
        else transactions.executeWithoutResult(status -> work.run());
        return get(operatorId, id);
    }

    @Override
    public SkillImportView cancel(long operatorId, long id, String revision) {
        SkillImportBean row = open(operatorId, imports.find(id));
        if (SkillImportState.CANCELLED.name().equals(row.getState())) return view(row);
        if (SkillImportState.CONFIRMED.name().equals(row.getState())) {
            fail(409, "IMPORT_ALREADY_CONFIRMED", "已确认的导入不能取消");
        }
        expect(row, revision);
        row.setGmtModified(SkillTimes.now());
        if (imports.cancel(row) != 1) throw currentConflict(id);
        return get(operatorId, id);
    }

    @Override
    public SkillImportView.SourceFile source(long operatorId, long id) {
        SkillImportBean row = open(operatorId, imports.find(id));
        byte[] body = packageBody(id, operatorId);
        if (body == null || row.getSourceAvailable() == null || Objects.equals(row.getSourceAvailable(), SkillImportBean.SOURCE_MISSING)) {
            fail(410, "SOURCE_PURGED", "原包已清理，不能下载");
        }
        return new SkillImportView.SourceFile(row.getFilename(), body);
    }

    private void confirmLocked(
            long operatorId, long id, long revision, boolean acknowledgeExisting, List<String> acknowledged) {
        SkillImportBean row = imports.lock(id);
        if (row == null || row.getCreatedBy() == null || row.getCreatedBy() != operatorId) {
            fail(404, "IMPORT_NOT_FOUND", "导入批次不存在");
        }
        if (SkillImportState.CONFIRMED.name().equals(row.getState()) && row.getResultVersionId() != null) return;
        if (due(row)) {
            imports.expire(id, row.getRevision(), SkillTimes.now());
            fail(410, "IMPORT_EXPIRED", "导入批次已过期");
        }
        if (row.getRevision() == null || row.getRevision() != revision) throw SkillSupport.conflict(row.getRevision());
        if (!SkillImportState.CHECKED.name().equals(row.getState())) fail(422, "CHECK_BLOCKED", "检查尚未完成");
        byte[] body = row.getPackageBody();
        if (body == null) fail(410, "SOURCE_PURGED", "原包已清理，不能确认");
        SkillPackageInspector.Inspection inspection = SkillPackageInspector.inspect(row.getFilename(), body);
        if (inspection.structural()) fail(422, "CHECK_BLOCKED", "包结构损坏，请重新上传");
        SkillCheckDocument document = document(row);
        List<SkillImportView.Blocker> blockers = blockers(document);
        if (!blockers.isEmpty()) {
            throw new SkillException(422, "CHECK_BLOCKED", "还有未处理的检查项", Map.of("blockers", blockers));
        }
        boolean exists = skills.findGoverned(row.getTargetName()) != null;
        if (exists && !acknowledgeExisting) {
            throw new SkillException(422, "CHECK_BLOCKED", "同名技能已存在，确认后只新增草稿，不会覆盖当前内容",
                    Map.of("targetExists", true));
        }
        List<String> requiredAcks = requiredAcks(document);
        if (!new HashSet<>(acknowledged).containsAll(requiredAcks)) {
            throw new SkillException(422, "CHECK_BLOCKED", "还有需要确认的警告",
                    Map.of("ruleKeys", requiredAcks));
        }
        document.confirmations = List.copyOf(acknowledged);
        SkillEntryPolicy entry = SkillEntryPolicy.parse(row.getEntryPolicy());
        SkillContentPolicy content = SkillContentPolicy.parse(row.getContentPolicy());
        String proposed = SkillPackageInspector.propose(inspection, row.getTargetName(), entry, content);
        SkillBean runtime = skills.findGoverned(row.getTargetName());
        long owner = operatorId;
        Long skillId = null;
        if (runtime != null) {
            skillId = runtime.getId();
            if (runtime.getCreateBy() != null) owner = runtime.getCreateBy();
        }
        LocalDateTime now = SkillTimes.now();
        SkillVersionBean version = new SkillVersionBean();
        version.setSkillName(row.getTargetName());
        version.setSkillId(skillId);
        version.setPackageVersion(inspection.packageVersion());
        version.setState(SkillVersionState.DRAFT.name());
        version.setDescription(inspection.description());
        version.setSkillContent(proposed);
        version.setMetadataJson(SkillSupport.metadata(row.getTargetName(), inspection.description()));
        version.setSourceRulesJson(SkillJson.write(rulesOf(inspection, entry, content)));
        version.setVisibleScope(document.placement.visibleScope);
        version.setOwnerDeptId(document.placement.ownerDeptId);
        version.setCreatedBy(owner);
        version.setImportId(row.getId());
        version.setRevision(0L);
        version.setGmtCreate(now);
        version.setGmtModified(now);
        insertVersion(version);
        List<SkillVersionFileBean> fileRows = new ArrayList<>();
        for (SkillPackageInspector.FileBytes file : inspection.files()) fileRows.add(fileRow(version.getId(), file, now));
        if (!fileRows.isEmpty()) files.insertFiles(fileRows);
        int pending = 0;
        List<SkillVersionDependencyBean> dependencyRows = new ArrayList<>();
        for (SkillCheckDocument.DependencyDraft draft : document.dependencyDrafts) {
            if (SkillMappingState.PENDING.name().equals(draft.mappingState)) pending++;
            dependencyRows.add(dependencyRow(version.getId(), draft, now));
        }
        if (!dependencyRows.isEmpty()) dependencies.insertDependencies(dependencyRows);
        List<String> warnings = warnings(document);
        SkillImportView.Result result = new SkillImportView.Result(
                SkillSupport.id(version.getId()),
                version.getSkillName(),
                SkillVersionState.DRAFT.name(),
                version.getPackageVersion(),
                null,
                inspection.files().size(),
                pending,
                content.name(),
                warnings);
        row.setConfirmedBy(operatorId);
        row.setConfirmedAt(now);
        row.setResultVersionId(version.getId());
        row.setResultJson(SkillJson.write(result));
        row.setCheckReportJson(SkillJson.write(document));
        row.setGmtModified(now);
        if (imports.confirm(row) != 1) throw currentConflict(id);
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
        if (last != null && SkillSupport.duplicate(last)) {
            fail(409, "VERSION_CONFLICT", "版本序号冲突，请重试");
        }
        if (last != null) throw last;
    }

    private static SkillVersionFileBean fileRow(long versionId, SkillPackageInspector.FileBytes file, LocalDateTime now) {
        SkillVersionFileBean row = new SkillVersionFileBean();
        row.setVersionId(versionId);
        row.setResourcePath(file.path());
        row.setFileKind(file.kind().name());
        row.setContent(file.body());
        row.setContentSha256(SkillSupport.sha256(file.body()));
        row.setByteSize(file.body().length);
        row.setGmtCreate(now);
        row.setGmtModified(now);
        return row;
    }

    private static SkillVersionDependencyBean dependencyRow(long versionId, SkillCheckDocument.DependencyDraft draft, LocalDateTime now) {
        SkillVersionDependencyBean row = new SkillVersionDependencyBean();
        row.setVersionId(versionId);
        row.setSourceKey(draft.sourceKey);
        row.setCapabilityKey(draft.capabilityKey);
        row.setRequiredFlag(draft.required ? SkillVersionDependencyBean.REQUIRED : SkillVersionDependencyBean.OPTIONAL);
        row.setTargetKind(draft.targetKind);
        row.setTargetProviderKey(draft.targetProviderKey);
        row.setTargetToolKey(draft.targetToolKey);
        row.setMappingState(draft.mappingState);
        row.setReason(draft.reason);
        row.setGmtCreate(now);
        row.setGmtModified(now);
        return row;
    }

    private SkillCheckDocument.DependencyDraft apply(SkillCheckDocument.DependencyDraft source, DependencyInput item) {
        SkillTargetKind kind = SkillTargetKind.parse(item.targetKind());
        SkillMappingState mapping = SkillMappingState.parse(item.mappingState());
        if (mapping == SkillMappingState.SUGGESTED) fail(422, "CHECK_BLOCKED", "建议映射还需要管理员确认");
        SkillCheckDocument.DependencyDraft draft = copy(source);
        if (mapping == SkillMappingState.PENDING) {
            if (kind != SkillTargetKind.UNRESOLVED || hasText(item.targetProviderKey()) || hasText(item.targetToolKey())) {
                fail(422, "CHECK_BLOCKED", "待接入依赖不能带目标");
            }
            if (!hasText(item.reason()) || item.reason().length() > 1000) {
                fail(422, "CHECK_BLOCKED", "待接入依赖需要原因");
            }
            draft.targetKind = kind.name();
            draft.targetProviderKey = null;
            draft.targetToolKey = null;
            draft.mappingState = mapping.name();
            draft.reason = item.reason().trim();
            return draft;
        }
        if (kind == SkillTargetKind.UNRESOLVED) fail(422, "CHECK_BLOCKED", "已确认的依赖不能是未解析");
        if (kind == SkillTargetKind.MCP_TOOL && (!hasText(item.targetProviderKey()) || !hasText(item.targetToolKey()))) {
            fail(422, "CHECK_BLOCKED", "MCP 工具需要服务和工具标识");
        }
        if (kind == SkillTargetKind.PLATFORM && (hasText(item.targetProviderKey()) || !hasText(item.targetToolKey()))) {
            fail(422, "CHECK_BLOCKED", "平台能力只填写能力标识");
        }
        requireTarget(kind, item.targetProviderKey(), item.targetToolKey(), source.capabilityKey);
        draft.targetKind = kind.name();
        draft.targetProviderKey = kind == SkillTargetKind.PLATFORM ? null : item.targetProviderKey();
        draft.targetToolKey = item.targetToolKey();
        draft.mappingState = SkillMappingState.CONFIRMED.name();
        draft.reason = hasText(item.reason()) ? item.reason().trim() : null;
        return draft;
    }

    private void requireTarget(SkillTargetKind kind, String provider, String tool, String capability) {
        SkillResourceCatalog.CatalogView view = catalog.capture(kind == SkillTargetKind.MCP_TOOL);
        for (SkillResourceCatalog.SkillResourceOption option : view.options()) {
            if (!option.available() || !option.targetKind().equals(kind.name())) continue;
            if (!tool.equals(option.toolKey())) continue;
            if (kind == SkillTargetKind.MCP_TOOL && !provider.equals(option.providerKey())) continue;
            if (capability != null && option.capabilityKeys() != null && !option.capabilityKeys().isEmpty()
                    && !option.capabilityKeys().contains(capability)) {
                fail(422, "DEPENDENCY_UNAVAILABLE", "目标能力不满足要求");
            }
            return;
        }
        fail(422, "DEPENDENCY_UNAVAILABLE", "目标能力不存在或不可用");
    }

    private SkillImportView view(SkillImportBean row) {
        SkillCheckDocument document = document(row);
        boolean exists = row.getTargetName() != null && skills.findGoverned(row.getTargetName()) != null;
        List<SkillImportView.Blocker> blockers = blockers(document);
        boolean terminal = !SkillImportState.parse(row.getState()).open()
                || SkillImportState.FAILED.name().equals(row.getState());
        boolean canConfirm = SkillImportState.CHECKED.name().equals(row.getState()) && blockers.isEmpty();
        SkillImportView.Configuration configuration = row.getTargetName() == null ? null : new SkillImportView.Configuration(
                row.getTargetName(), row.getEntryPolicy(), row.getContentPolicy(),
                document.placement == null ? null : document.placement.visibleScope,
                document.placement == null ? null : document.placement.ownerDeptId);
        SkillImportView.PackageInfo info = document.normalizedPackage == null || document.normalizedPackage.name == null
                ? null
                : new SkillImportView.PackageInfo(
                        document.normalizedPackage.name,
                        document.normalizedPackage.version,
                        document.normalizedPackage.fileCount);
        List<SkillImportView.Dependency> deps = new ArrayList<>();
        for (SkillCheckDocument.DependencyDraft draft : document.dependencyDrafts) {
            deps.add(new SkillImportView.Dependency(
                    draft.sourceKey, draft.capabilityKey, draft.required, draft.gate, draft.targetKind,
                    draft.targetProviderKey, draft.targetToolKey, draft.mappingState, draft.reason));
        }
        return new SkillImportView(
                SkillSupport.id(row.getId()),
                SkillSupport.id(row.getRevision()),
                row.getState(),
                step(row, document),
                row.getFilename(),
                row.getPackageSha256(),
                Objects.equals(row.getSourceAvailable(), SkillImportBean.SOURCE_AVAILABLE),
                SkillTimes.format(row.getExpiresAt()),
                configuration,
                info,
                checks(document),
                deps,
                canConfirm && !terminal,
                exists,
                blockers,
                SkillJson.read(row.getResultJson(), SkillImportView.Result.class));
    }

    private static List<SkillImportView.Check> checks(SkillCheckDocument document) {
        List<SkillImportView.Check> checks = new ArrayList<>();
        for (SkillPackageInspector.Check check : document.checks) {
            checks.add(new SkillImportView.Check(
                    check.ruleKey(), check.level(), check.result(), check.message(), check.acknowledge()));
        }
        return checks;
    }

    private static List<SkillImportView.Blocker> blockers(SkillCheckDocument document) {
        List<SkillImportView.Blocker> blockers = new ArrayList<>();
        for (SkillPackageInspector.Check check : document.checks) {
            if ("ERROR".equals(check.level()) && "FAIL".equals(check.result())) {
                blockers.add(new SkillImportView.Blocker("CHECK_BLOCKED", null, check.ruleKey(), check.message()));
            }
        }
        for (SkillCheckDocument.DependencyDraft draft : document.dependencyDrafts) {
            if (!SkillMappingState.CONFIRMED.name().equals(draft.mappingState) && !SkillMappingState.PENDING.name().equals(draft.mappingState)) {
                blockers.add(new SkillImportView.Blocker(
                        "MAPPING_NOT_CONFIRMED", draft.sourceKey, null, "依赖尚未确认"));
            } else if (SkillMappingState.PENDING.name().equals(draft.mappingState) && !hasText(draft.reason)) {
                blockers.add(new SkillImportView.Blocker("REASON_REQUIRED", draft.sourceKey, null, "待接入依赖需要原因"));
            } else if (SkillMappingState.PENDING.name().equals(draft.mappingState) && draft.required && "confirm".equals(draft.gate)) {
                blockers.add(new SkillImportView.Blocker(
                        "MAPPING_NOT_CONFIRMED", draft.sourceKey, null, "该依赖必须在导入前绑定"));
            }
        }
        return blockers;
    }

    private static List<String> requiredAcks(SkillCheckDocument document) {
        List<String> keys = new ArrayList<>();
        for (SkillPackageInspector.Check check : document.checks) {
            if (check.acknowledge() && ("WARN".equals(check.result()) || "FAIL".equals(check.result()))) keys.add(check.ruleKey());
        }
        for (SkillPackageInspector.SourceRule rule : document.normalizedPackage.rules) {
            if (rule.acknowledge() && ("confirm".equals(rule.blocks()) || "both".equals(rule.blocks()))) keys.add(rule.key());
        }
        return keys;
    }

    private static List<String> warnings(SkillCheckDocument document) {
        List<String> warnings = new ArrayList<>();
        for (SkillPackageInspector.Check check : document.checks) {
            if ("WARN".equals(check.level())) warnings.add(check.message());
        }
        for (SkillCheckDocument.DependencyDraft draft : document.dependencyDrafts) {
            if (SkillMappingState.PENDING.name().equals(draft.mappingState)) {
                warnings.add("仍有待接入依赖");
                break;
            }
        }
        return warnings;
    }

    private static SkillCheckDocument baseDocument(SkillPackageInspector.Inspection inspection) {
        SkillCheckDocument document = new SkillCheckDocument();
        document.structural = inspection.structural();
        document.checks = new ArrayList<>(inspection.checks());
        fillNormalized(document, inspection);
        return document;
    }

    private static SkillCheckDocument reviewed(
            SkillPackageInspector.Inspection inspection, String scope, Long department,
            SkillResourceCatalog.CatalogView tools, boolean catalogFailed, boolean targetExists) {
        SkillCheckDocument document = baseDocument(inspection);
        document.placement.visibleScope = scope;
        document.placement.ownerDeptId = department;
        if (targetExists) {
            document.checks.add(new SkillPackageInspector.Check(
                    "TARGET_EXISTS", "WARN", "WARN", "同名技能已存在，确认后只新增草稿版本，不覆盖当前内容", true));
        }
        for (SkillPackageInspector.SourceRule rule : inspection.rules()) {
            document.checks.add(new SkillPackageInspector.Check(
                    rule.key(), rule.level(), "ERROR".equals(rule.level()) ? "FAIL" : "WARN", rule.message(),
                    rule.acknowledge()));
        }
        if (catalogFailed) {
            document.checks.add(new SkillPackageInspector.Check(
                    "RESOURCE_CATALOG", "WARN", "FAIL", "工具目录暂不可用，未生成可用的建议映射", false));
        }
        for (SkillPackageInspector.SourceDependency dependency : inspection.dependencies()) {
            document.dependencyDrafts.add(suggest(dependency, catalogFailed ? null : tools));
        }
        return document;
    }

    private static void fillNormalized(SkillCheckDocument document, SkillPackageInspector.Inspection inspection) {
        document.normalizedPackage.name = inspection.name();
        document.normalizedPackage.version = inspection.packageVersion();
        document.normalizedPackage.description = inspection.description();
        document.normalizedPackage.fileCount = inspection.files().size();
        document.normalizedPackage.dependencies = new ArrayList<>(inspection.dependencies());
        document.normalizedPackage.rules = new ArrayList<>(inspection.rules());
        for (SkillPackageInspector.FileBytes file : inspection.files()) {
            SkillCheckDocument.FileRef ref = new SkillCheckDocument.FileRef();
            ref.path = file.path();
            ref.kind = file.kind().name();
            ref.byteSize = file.body().length;
            ref.sha256 = SkillSupport.sha256(file.body());
            document.normalizedPackage.files.add(ref);
        }
    }

    private static SkillCheckDocument.DependencyDraft suggest(
            SkillPackageInspector.SourceDependency dependency, SkillResourceCatalog.CatalogView tools) {
        SkillCheckDocument.DependencyDraft draft = new SkillCheckDocument.DependencyDraft();
        draft.sourceKey = dependency.sourceKey();
        draft.capabilityKey = dependency.capabilityKey();
        draft.required = dependency.required();
        draft.gate = dependency.gate();
        draft.targetKind = SkillTargetKind.UNRESOLVED.name();
        draft.mappingState = SkillMappingState.PENDING.name();
        if (tools == null) return draft;
        for (SkillResourceCatalog.SkillResourceOption option : tools.options()) {
            if (!option.available()) continue;
            boolean name = option.toolKey().equals(dependency.sourceKey());
            boolean capability = dependency.capabilityKey() != null && option.capabilityKeys() != null
                    && option.capabilityKeys().contains(dependency.capabilityKey());
            if (!name && !capability) continue;
            draft.targetKind = option.targetKind();
            draft.targetProviderKey = option.providerKey();
            draft.targetToolKey = option.toolKey();
            draft.mappingState = SkillMappingState.SUGGESTED.name();
            draft.reason = null;
            break;
        }
        return draft;
    }

    private static SkillCheckDocument.DependencyDraft copy(SkillCheckDocument.DependencyDraft source) {
        SkillCheckDocument.DependencyDraft draft = new SkillCheckDocument.DependencyDraft();
        draft.sourceKey = source.sourceKey;
        draft.capabilityKey = source.capabilityKey;
        draft.required = source.required;
        draft.gate = source.gate;
        return draft;
    }

    private static SkillSourceRules rulesOf(
            SkillPackageInspector.Inspection inspection, SkillEntryPolicy entry, SkillContentPolicy content) {
        SkillSourceRules rules = new SkillSourceRules();
        rules.entryPolicy = entry.name();
        rules.contentPolicy = content.name();
        rules.packageVersion = inspection.packageVersion();
        rules.dependencies = new ArrayList<>(inspection.dependencies());
        rules.rules = new ArrayList<>(inspection.rules());
        return rules;
    }

    private SkillCheckDocument document(SkillImportBean row) {
        SkillCheckDocument document = SkillJson.read(row.getCheckReportJson(), SkillCheckDocument.class);
        if (document == null) document = new SkillCheckDocument();
        if (document.checks == null) document.checks = new ArrayList<>();
        if (document.dependencyDrafts == null) document.dependencyDrafts = new ArrayList<>();
        if (document.confirmations == null) document.confirmations = new ArrayList<>();
        if (document.normalizedPackage == null) document.normalizedPackage = new SkillCheckDocument.Normalized();
        if (document.normalizedPackage.files == null) document.normalizedPackage.files = new ArrayList<>();
        if (document.normalizedPackage.dependencies == null) document.normalizedPackage.dependencies = new ArrayList<>();
        if (document.normalizedPackage.rules == null) document.normalizedPackage.rules = new ArrayList<>();
        if (document.placement == null) document.placement = new SkillCheckDocument.Placement();
        return document;
    }

    private SkillImportBean open(long operatorId, SkillImportBean row) {
        if (row == null || row.getCreatedBy() == null || row.getCreatedBy() != operatorId) {
            fail(404, "IMPORT_NOT_FOUND", "导入批次不存在");
        }
        if (due(row)) {
            imports.expire(row.getId(), row.getRevision() == null ? 0 : row.getRevision(), SkillTimes.now());
            fail(410, "IMPORT_EXPIRED", "导入批次已过期");
        }
        return row;
    }

    private byte[] packageBody(long id, long operatorId) {
        SkillImportBean stored = imports.findBody(id, operatorId);
        return stored == null ? null : stored.getPackageBody();
    }

    private static boolean due(SkillImportBean row) {
        if (row.getExpiresAt() == null || row.getState() == null) return false;
        SkillImportState state = SkillImportState.parse(row.getState());
        return state.open() && SkillTimes.now().isAfter(row.getExpiresAt());
    }

    private static String step(SkillImportBean row, SkillCheckDocument document) {
        if (SkillImportState.CONFIRMED.name().equals(row.getState())) return "RESULT";
        if (SkillImportState.CHECKED.name().equals(row.getState())) return "RESOURCES";
        if (SkillImportState.UPLOADED.name().equals(row.getState())) return "REVIEW";
        if (SkillImportState.FAILED.name().equals(row.getState()) && document != null && !document.structural) {
            return "REVIEW";
        }
        return "FILE";
    }

    private static void expect(SkillImportBean row, String revision) {
        long expected = SkillSupport.revision(revision);
        long current = row.getRevision() == null ? 0 : row.getRevision();
        if (current != expected) throw SkillSupport.conflict(current);
    }

    private SkillException currentConflict(long id) {
        SkillImportBean current = imports.find(id);
        return SkillSupport.conflict(current == null || current.getRevision() == null ? 0 : current.getRevision());
    }

    private Long department(long operatorId, SkillVisibleScope scope, Long ownerDeptId) {
        if (scope != SkillVisibleScope.DEPARTMENT) return null;
        if (ownerDeptId == null) fail(400, "INVALID_ARGUMENT", "本部门可见需要明确部门");
        if (!visibility.departmentActive(ownerDeptId)) fail(400, "INVALID_ARGUMENT", "部门不存在或已停用");
        return ownerDeptId;
    }

    private static SkillVisibleScope scope(String visibleScope) {
        if (visibleScope == null || visibleScope.isBlank()) fail(400, "INVALID_ARGUMENT", "可见范围不能为空");
        return SkillVisibleScope.parse(visibleScope);
    }

    private static String name(String targetName) {
        if (targetName == null || !SkillDocuments.NAME.matcher(targetName).matches()) {
            fail(400, "INVALID_NAME", "技能名不合法");
        }
        return targetName;
    }

    private static String requestKey(String requestKey) {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 64 || requestKey.chars().anyMatch(ch -> ch < 0x20)) {
            fail(400, "INVALID_ARGUMENT", "Idempotency-Key 需要 1 到 64 个字符");
        }
        return requestKey;
    }

    private static String filename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255 || filename.indexOf('\0') >= 0) {
            fail(400, "INVALID_ARGUMENT", "文件名不合法");
        }
        return filename.trim();
    }

    private static void page(int page, int size) {
        if (!Pages.valid(page, size)) fail(400, "INVALID_ARGUMENT", "分页参数不合法");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void fail(int status, String code, String message) {
        throw new SkillException(status, code, message);
    }
}
