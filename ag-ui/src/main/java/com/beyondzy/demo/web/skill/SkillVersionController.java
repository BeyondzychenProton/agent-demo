package com.beyondzy.demo.web.skill;

import com.beyondzy.demo.service.skill.SkillSupport;
import com.beyondzy.demo.service.skill.SkillVersionService;
import com.beyondzy.demo.service.skill.SkillVersionView;
import com.beyondzy.demo.service.user.UserService;
import com.beyondzy.demo.web.skill.req.CreateSkillVersionRequest;
import com.beyondzy.demo.web.skill.req.PublishSkillVersionRequest;
import com.beyondzy.demo.web.skill.req.UpdateSkillVersionRequest;
import com.beyondzy.demo.web.skill.req.ValidateSkillVersionRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 技能草稿和发布。管理员可以维护版本，发布规则仍由服务端执行。 */
@RestController
@RequestMapping("/api/admin/skill-versions")
public class SkillVersionController {
    private final SkillVersionService versions;
    private final UserService users;

    public SkillVersionController(SkillVersionService versions, UserService users) {
        this.versions = versions;
        this.users = users;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<SkillVersionView>> create(@RequestBody CreateSkillVersionRequest request) {
        return admin().flatMap(id -> Mono.fromCallable(() -> {
            SkillVersionView created = versions.create(id, new SkillVersionService.CreateRequest(
                    request.skillName(), request.baseVersionId(), request.description(), request.content(),
                    request.visibleScope(), request.ownerDeptId(), files(request.files())));
            return ResponseEntity.created(URI.create("/api/admin/skill-versions/" + created.id())).body(created);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SkillVersionView.Page> list(
            @RequestParam(required = false) String skillName,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admin().flatMap(id -> Mono.fromCallable(() -> versions.list(skillName, state, page, size))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SkillVersionView> get(@PathVariable String id) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> versions.get(versionId(id)))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SkillVersionView> update(@PathVariable String id, @RequestBody UpdateSkillVersionRequest request) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> versions.update(actor, versionId(id),
                        new SkillVersionService.UpdateRequest(
                                request.revision(), request.description(), request.content(), request.visibleScope(),
                                request.ownerDeptId(), request.dependencies(), updateFiles(request.files()), request.displayName())))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/{id}/files/{fileId}")
    public Mono<ResponseEntity<byte[]>> file(
            @PathVariable String id, @PathVariable String fileId,
            @RequestParam(defaultValue = "false") boolean download) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> {
            SkillVersionView.FileBody file = versions.file(versionId(id), fileId(fileId), download);
            MediaType type = file.attachment()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType("text/plain;charset=UTF-8");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, SkillImportController.disposition(file.filename())
                            .replace("attachment", file.attachment() ? "attachment" : "inline"))
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(type)
                    .body(file.body());
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping(value = "/{id}/validate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SkillVersionView.Validation> validate(
            @PathVariable String id, @RequestBody ValidateSkillVersionRequest request) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> versions.validate(versionId(id), request.revision()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping(value = "/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SkillVersionView.Published> publish(
            @PathVariable String id, @RequestBody PublishSkillVersionRequest request) {
        return admin().flatMap(actor -> Mono.fromCallable(() -> versions.publish(actor, versionId(id),
                        new SkillVersionService.PublishRequest(
                                request.revision(), request.platformVersion(), request.acknowledgedRuleKeys())))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Long> admin() {
        return SkillAdminAccess.requireAdmin(users);
    }

    private static long versionId(String id) {
        return SkillSupport.positiveId(id, "VERSION_NOT_FOUND", "版本不存在");
    }

    private static long fileId(String id) {
        return SkillSupport.positiveId(id, "FILE_NOT_FOUND", "文件不存在");
    }

    private static List<SkillVersionService.TextFile> files(List<CreateSkillVersionRequest.TextFile> files) {
        if (files == null) return List.of();
        return files.stream().map(file -> new SkillVersionService.TextFile(file.path(), file.content())).toList();
    }

    private static List<SkillVersionService.UpdateFile> updateFiles(List<UpdateSkillVersionRequest.UpdateFile> files) {
        if (files == null) return List.of();
        return files.stream()
                .map(file -> new SkillVersionService.UpdateFile(file.fileId(), file.path(), file.content()))
                .toList();
    }
}
