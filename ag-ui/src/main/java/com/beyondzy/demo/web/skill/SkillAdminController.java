package com.beyondzy.demo.web.skill;

import com.beyondzy.demo.service.skill.SkillAdminService;
import com.beyondzy.demo.service.skill.SkillAdminView;
import com.beyondzy.demo.service.skill.SkillDraft;
import com.beyondzy.demo.service.user.UserService;
import com.beyondzy.demo.web.skill.req.SaveSkillRequest;
import com.beyondzy.demo.web.skill.req.SkillStatusRequest;
import com.beyondzy.demo.web.skill.res.SkillAdminDetailResponse;
import com.beyondzy.demo.web.skill.res.SkillAdminPageResponse;
import com.beyondzy.demo.web.skill.res.SkillOptionResponse;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 平台管理员维护技能。options 注册在 /{name} 前面，避免被当成技能名。 */
@RestController
@RequestMapping(value = "/api/admin/skills", produces = MediaType.APPLICATION_JSON_VALUE)
public class SkillAdminController {
    private final SkillAdminService skills;
    private final UserService users;

    public SkillAdminController(SkillAdminService skills, UserService users) {
        this.skills = skills;
        this.users = users;
    }

    @GetMapping
    public Mono<SkillAdminPageResponse> list(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "all") String state,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admin().flatMap(id -> Mono.fromCallable(() -> page(skills.list(query, state, page, size)))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/options")
    public Mono<List<SkillOptionResponse>> options() {
        return admin().flatMap(id -> Mono.fromCallable(() -> skills.options(id).stream()
                        .map(option -> new SkillOptionResponse(option.name(), option.description()))
                        .toList())
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/{name}")
    public Mono<SkillAdminDetailResponse> detail(@PathVariable String name) {
        return admin().flatMap(id -> Mono.fromCallable(() -> detail(skills.detail(name)))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping
    public Mono<SkillAdminDetailResponse> create(@RequestBody SaveSkillRequest request) {
        return admin().flatMap(id -> Mono.fromCallable(() -> detail(skills.create(id, draft(request))))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PutMapping("/{name}")
    public Mono<SkillAdminDetailResponse> update(@PathVariable String name, @RequestBody SaveSkillRequest request) {
        return admin().flatMap(id -> Mono.fromCallable(() -> detail(skills.update(id, name, draft(request))))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/{name}/status")
    public Mono<Void> status(@PathVariable String name, @RequestBody SkillStatusRequest request) {
        return admin().flatMap(id -> Mono.<Void>fromRunnable(() -> skills.setEnabled(name, request.enabled()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @DeleteMapping("/{name}")
    public Mono<Void> delete(@PathVariable String name) {
        return admin().flatMap(id -> Mono.<Void>fromRunnable(() -> skills.delete(name))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SkillAdminDetailResponse> importSkill(
            @RequestPart("file") FilePart file,
            @RequestParam(required = false) String visibleScope,
            @RequestParam(required = false) Long ownerDeptId,
            @RequestParam(required = false) String status) {
        return admin().flatMap(id -> bytes(file).flatMap(body -> Mono.fromCallable(() -> detail(
                        skills.importDocument(id, file.filename(), body, visibleScope, ownerDeptId, status)))
                .subscribeOn(Schedulers.boundedElastic())));
    }

    private Mono<Long> admin() {
        return SkillAdminAccess.requireAdmin(users);
    }

    private static SkillDraft draft(SaveSkillRequest request) {
        List<SkillDraft.SkillFile> files = request.files() == null
                ? List.of()
                : request.files().stream()
                        .map(file -> new SkillDraft.SkillFile(file.path(), file.content()))
                        .toList();
        return new SkillDraft(
                request.name(),
                request.description(),
                request.content(),
                request.visibleScope(),
                request.ownerDeptId(),
                request.status(),
                files);
    }

    private static SkillAdminPageResponse page(SkillAdminView.Page page) {
        return new SkillAdminPageResponse(
                page.items().stream()
                        .map(item -> new SkillAdminPageResponse.Item(
                                item.name(),
                                item.description(),
                                item.status(),
                                item.visibleScope(),
                                item.ownerDeptId(),
                                item.ownerDeptName(),
                                item.resourceCount(),
                                item.bound(),
                                item.updatedAt()))
                        .toList(),
                page.total(),
                page.page(),
                page.size());
    }

    private static SkillAdminDetailResponse detail(SkillAdminView.Detail detail) {
        return new SkillAdminDetailResponse(
                detail.name(),
                detail.description(),
                detail.content(),
                detail.status(),
                detail.visibleScope(),
                detail.ownerDeptId(),
                detail.ownerDeptName(),
                detail.bound(),
                detail.updatedAt(),
                detail.files().stream()
                        .map(file -> new SkillAdminDetailResponse.SkillFileResponse(file.path(), file.content()))
                        .toList());
    }

    private static Mono<byte[]> bytes(FilePart file) {
        return DataBufferUtils.join(file.content()).map(SkillAdminController::copy);
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
}
