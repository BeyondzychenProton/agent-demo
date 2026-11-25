package com.beyondzy.demo.web.skill;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.service.skill.SkillAdminService;
import com.beyondzy.demo.contract.PageResult;
import com.beyondzy.demo.service.skill.view.SkillAdminView;
import com.beyondzy.demo.service.user.UserService;
import com.beyondzy.demo.web.skill.req.SaveSkillRequest;
import com.beyondzy.demo.web.skill.req.SkillStatusRequest;
import com.beyondzy.demo.web.skill.res.SkillAdminDetailResponse;
import com.beyondzy.demo.web.skill.res.SkillAdminPageResponse;
import com.beyondzy.demo.web.skill.res.SkillOptionResponse;
import java.util.List;
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
@Tag(name = "技能维护", description = "平台管理员维护技能")
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
    @Operation(summary = "分页查询技能")
    public Mono<SkillAdminPageResponse> list(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "all") String state,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admin().flatMap(id -> Mono.fromCallable(() -> page(skills.list(query, state, page, size)))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/options")
    @Operation(summary = "查询可绑定的技能")
    public Mono<List<SkillOptionResponse>> options() {
        return admin().flatMap(id -> Mono.fromCallable(() -> skills.options(id).stream()
                        .map(option -> new SkillOptionResponse(option.name(), option.description(), option.currentVersionId(), option.versions()))
                        .toList())
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询技能详情")
    public Mono<SkillAdminDetailResponse> detail(@PathVariable String name) {
        return admin().flatMap(id -> Mono.fromCallable(() -> detail(skills.detail(name)))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping
    @Operation(summary = "创建技能")
    public Mono<SkillAdminDetailResponse> create(@RequestBody SaveSkillRequest request) {
        return Mono.error(new SkillException(410, "LEGACY_WRITE_DISABLED", "请通过技能草稿创建，发布后才会写入运行内容"));
    }

    @PutMapping("/{name}")
    @Operation(summary = "保存技能")
    public Mono<SkillAdminDetailResponse> update(@PathVariable String name, @RequestBody SaveSkillRequest request) {
        return Mono.error(new SkillException(410, "LEGACY_WRITE_DISABLED", "请通过技能草稿修改，发布后才会更新运行内容"));
    }

    @PostMapping("/{name}/status")
    @Operation(summary = "修改技能启停")
    public Mono<Void> status(@PathVariable String name, @RequestBody SkillStatusRequest request) {
        return admin().flatMap(id -> Mono.<Void>fromRunnable(() -> skills.setEnabled(name, request.enabled()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除技能")
    public Mono<Void> delete(@PathVariable String name) {
        return admin().flatMap(id -> Mono.<Void>fromRunnable(() -> skills.delete(name))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "导入技能文档")
    public Mono<SkillAdminDetailResponse> importSkill(
            @RequestPart("file") FilePart file,
            @RequestParam(required = false) String visibleScope,
            @RequestParam(required = false) Long ownerDeptId,
            @RequestParam(required = false) String status) {
        return Mono.error(new SkillException(410, "LEGACY_IMPORT_DISABLED", "请使用导入批次，不要直接写入运行技能"));
    }

    private Mono<Long> admin() {
        return SkillAdminAccess.requireAdmin(users);
    }

    private static SkillAdminPageResponse page(PageResult<SkillAdminView.Item> page) {
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
}
