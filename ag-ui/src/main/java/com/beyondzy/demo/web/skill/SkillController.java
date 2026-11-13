package com.beyondzy.demo.web.skill;

import com.beyondzy.demo.contract.AuthPrincipal;
import com.beyondzy.demo.handler.admin.CurrentUser;
import com.beyondzy.demo.service.skill.SkillCatalogService;
import com.beyondzy.demo.service.skill.SkillPage;
import com.beyondzy.demo.service.skill.SkillStore;
import com.beyondzy.demo.service.skill.SkillUpload;
import com.beyondzy.demo.service.skill.SkillView;
import com.beyondzy.demo.web.skill.res.SkillDetailResponse;
import com.beyondzy.demo.web.skill.res.SkillPageResponse;
import com.beyondzy.demo.web.skill.res.SkillResourceResponse;
import com.beyondzy.demo.web.skill.res.SkillSummaryResponse;
import java.util.concurrent.Callable;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 技能目录。
 *
 * <p>权限：<b>仅管理员可上传/删除</b>；已登录用户可查看"平台技能 + 分给自己/自己的技能"。
 * 归属由 {@code ownerUserId} 指定：0 = 平台公共，N = 仅用户 N 可见。
 */
@RestController
@RequestMapping(value = "/api/skills", produces = MediaType.APPLICATION_JSON_VALUE)
public class SkillController {
    private final SkillCatalogService skills;

    public SkillController(SkillCatalogService skills) {
        this.skills = skills;
    }

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SkillDetailResponse> uploadFile(
            @RequestPart("file") FilePart file,
            @RequestParam(defaultValue = "false") boolean overwrite,
            @RequestParam(defaultValue = "0") long ownerUserId) {
        return requireAdmin()
                .then(bytes(file))
                .flatMap(body -> call(() -> skills.saveMarkdown(body, overwrite, ownerUserId)))
                .map(SkillController::detail);
    }

    @PostMapping(value = "/archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SkillDetailResponse> uploadArchive(
            @RequestPart("file") FilePart file,
            @RequestParam(defaultValue = "false") boolean overwrite,
            @RequestParam(defaultValue = "0") long ownerUserId) {
        return requireAdmin()
                .then(bytes(file))
                .flatMap(body -> call(() -> skills.saveArchive(body, overwrite, ownerUserId)))
                .map(SkillController::detail);
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SkillDetailResponse> uploadFiles(
            @RequestPart("files") Flux<FilePart> files,
            @RequestParam(defaultValue = "false") boolean overwrite,
            @RequestParam(defaultValue = "0") long ownerUserId) {
        return requireAdmin()
                .then(files
                        .concatMap(part -> bytes(part).map(body -> new SkillUpload(part.filename(), body)))
                        .collectList())
                .flatMap(list -> call(() -> skills.saveFiles(list, overwrite, ownerUserId)))
                .map(SkillController::detail);
    }

    /**
     * 技能列表。
     *
     * @param all 管理员可传 true 查看全量（含所有用户私有技能）
     */
    @GetMapping
    public Mono<SkillPageResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean all) {
        return CurrentUser.require()
                .flatMap(principal -> call(() -> all && principal.admin()
                        ? skills.listAll(page, size)
                        : skills.list(principal.userId(), page, size)))
                .map(SkillController::page);
    }

    /**
     * 技能详情。
     *
     * @param ownerUserId 不传 = 当前用户可见视图；传了按归属精确查（本人或管理员）
     */
    @GetMapping("/{name}")
    public Mono<SkillDetailResponse> detail(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean includeBodies,
            @RequestParam(required = false) Long ownerUserId) {
        return CurrentUser.require()
                .flatMap(principal -> call(() -> {
                    if (ownerUserId == null) {
                        return skills.detail(name, includeBodies, principal.userId());
                    }
                    return viewAs(principal, ownerUserId, name, includeBodies);
                }))
                .map(SkillController::detail);
    }

    /** 删除：仅管理员；ownerUserId 定位删除哪一层（0 = 平台）。 */
    @DeleteMapping("/{name}")
    public Mono<Void> delete(
            @PathVariable String name, @RequestParam(defaultValue = "0") long ownerUserId) {
        return requireAdmin()
                .then(call(() -> {
                    skills.delete(name, ownerUserId);
                    return true;
                }))
                .then();
    }

    // ------------------------------------------------------------------

    private SkillView viewAs(
            AuthPrincipal principal, long ownerUserId, String name, boolean includeBodies) {
        if (!principal.admin() && ownerUserId != principal.userId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该技能");
        }
        return ownerUserId == SkillStore.PLATFORM_OWNER
                ? skills.detail(name, includeBodies, principal.userId())
                : skills.detailByOwner(name, includeBodies, ownerUserId);
    }

    private static Mono<Void> requireAdmin() {
        return CurrentUser.require()
                .flatMap(principal -> principal.admin()
                        ? Mono.empty()
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "仅管理员可管理技能")));
    }

    private static <T> Mono<T> call(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<byte[]> bytes(FilePart file) {
        return DataBufferUtils.join(file.content()).map(SkillController::copy);
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

    private static SkillPageResponse page(SkillPage page) {
        return new SkillPageResponse(
                page.items().stream()
                        .map(item -> new SkillSummaryResponse(
                                item.name(),
                                item.description(),
                                item.ownerUserId(),
                                item.createdAt(),
                                item.updatedAt(),
                                item.resourceCount()))
                        .toList(),
                page.total(),
                page.page(),
                page.size());
    }

    private static SkillDetailResponse detail(SkillView view) {
        return new SkillDetailResponse(
                view.name(),
                view.description(),
                view.source(),
                view.ownerUserId(),
                view.createdAt(),
                view.updatedAt(),
                view.skillContent(),
                view.resources().stream()
                        .map(file -> new SkillResourceResponse(
                                file.path(), file.kind().name(), file.content()))
                        .toList());
    }
}
