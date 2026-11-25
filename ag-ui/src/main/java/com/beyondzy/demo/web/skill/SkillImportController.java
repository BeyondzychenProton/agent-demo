package com.beyondzy.demo.web.skill;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.service.skill.support.SkillDocuments;
import com.beyondzy.demo.service.skill.support.SkillSupport;
import com.beyondzy.demo.service.skill.SkillImportService;
import com.beyondzy.demo.contract.PageResult;
import com.beyondzy.demo.service.skill.view.SkillImportView;
import com.beyondzy.demo.service.skill.support.SkillPackageInspector;
import com.beyondzy.demo.service.user.UserService;
import com.beyondzy.demo.web.skill.req.ImportCancelRequest;
import com.beyondzy.demo.web.skill.req.ImportConfigRequest;
import com.beyondzy.demo.web.skill.req.ImportConfirmRequest;
import com.beyondzy.demo.web.skill.req.ImportDependenciesRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 技能包导入。批次只对创建它的管理员可见。 */
@Tag(name = "技能导入", description = "技能包导入，批次只对创建它的管理员可见")
@RestController
@RequestMapping("/api/admin/skill-imports")
public class SkillImportController {
    private final SkillImportService imports;
    private final UserService users;

    public SkillImportController(SkillImportService imports, UserService users) {
        this.imports = imports;
        this.users = users;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "上传技能包")
    public Mono<ResponseEntity<SkillImportView>> upload(
            @RequestHeader("Idempotency-Key") String requestKey, @RequestPart("file") FilePart file) {
        int max = SkillPackageInspector.markdownName(file.filename())
                ? SkillDocuments.SKILL_MD_LIMIT
                : SkillDocuments.ARCHIVE_LIMIT;
        return admin().flatMap(id -> limited(file, max).flatMap(body -> Mono.fromCallable(() -> {
            SkillImportView.Created created = imports.upload(id, requestKey, file.filename(), body);
            URI location = URI.create("/api/admin/skill-imports/" + created.view().id());
            return created.created()
                    ? ResponseEntity.created(location).body(created.view())
                    : ResponseEntity.ok(created.view());
        }).subscribeOn(Schedulers.boundedElastic())));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "分页查询导入批次")
    public Mono<PageResult<SkillImportView.Summary>> list(
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admin().flatMap(id -> Mono.fromCallable(() -> imports.list(id, state, page, size))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "查询导入批次")
    public Mono<SkillImportView> get(@PathVariable String id) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> imports.get(actor, importId(id)))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PutMapping(value = "/{id}/configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "保存导入配置")
    public Mono<SkillImportView> configure(@PathVariable String id, @RequestBody ImportConfigRequest request) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> imports.configure(
                        actor, importId(id), request.revision(), request.targetName(), request.entryPolicy(),
                        request.contentPolicy(), request.visibleScope(), request.ownerDeptId()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PutMapping(value = "/{id}/dependencies", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "保存依赖映射")
    public Mono<SkillImportView> dependencies(@PathVariable String id, @RequestBody ImportDependenciesRequest request) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> imports.saveDependencies(
                        actor, importId(id), request.revision(), request.items()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping(value = "/{id}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "确认导入")
    public Mono<SkillImportView> confirm(@PathVariable String id, @RequestBody ImportConfirmRequest request) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> imports.confirm(
                        actor, importId(id), request.revision(), request.acknowledgeExisting(),
                        request.acknowledgedRuleKeys()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "取消导入")
    public Mono<SkillImportView> cancel(@PathVariable String id, @RequestBody ImportCancelRequest request) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> imports.cancel(actor, importId(id), request.revision()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/{id}/source")
    @Operation(summary = "下载导入原件")
    public Mono<ResponseEntity<byte[]>> source(@PathVariable String id) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> {
            SkillImportView.SourceFile file = imports.source(actor, importId(id));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition(file.filename()))
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(file.body());
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Long> admin() {
        return SkillAdminAccess.requireAdmin(users);
    }

    private static long importId(String id) {
        return SkillSupport.positiveId(id, "IMPORT_NOT_FOUND", "导入批次不存在");
    }

    private static Mono<byte[]> limited(FilePart file, int max) {
        return DataBufferUtils.join(file.content(), max)
                .map(SkillImportController::copy)
                .onErrorMap(DataBufferLimitException.class,
                        error -> new SkillException(413, "UPLOAD_TOO_LARGE", "上传超过大小限制"));
    }

    private static byte[] copy(DataBuffer buffer) {
        try {
            byte[] body = new byte[buffer.readableByteCount()];
            buffer.read(body);
            return body;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    static String disposition(String filename) {
        String cleaned = filename == null ? "package.bin" : filename.replace("\r", "").replace("\n", "").replace("\"", "");
        String encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }
}
