package com.beyondzy.demo.web.skill;

import com.beyondzy.demo.service.skill.SkillCatalogService;
import com.beyondzy.demo.service.skill.SkillPage;
import com.beyondzy.demo.service.skill.SkillUpload;
import com.beyondzy.demo.service.skill.SkillView;
import com.beyondzy.demo.web.skill.res.SkillDetailResponse;
import com.beyondzy.demo.web.skill.res.SkillPageResponse;
import com.beyondzy.demo.web.skill.res.SkillResourceResponse;
import com.beyondzy.demo.web.skill.res.SkillSummaryResponse;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 已登录用户都可以管理全局技能。不提供模型侧的技能管理工具。 */
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
            @RequestParam(defaultValue = "false") boolean overwrite) {
        return bytes(file)
                .flatMap(body -> Mono.fromCallable(() -> skills.saveMarkdown(body, overwrite))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(SkillController::detail);
    }

    @PostMapping(value = "/archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SkillDetailResponse> uploadArchive(
            @RequestPart("file") FilePart file,
            @RequestParam(defaultValue = "false") boolean overwrite) {
        return bytes(file)
                .flatMap(body -> Mono.fromCallable(() -> skills.saveArchive(body, overwrite))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(SkillController::detail);
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SkillDetailResponse> uploadFiles(
            @RequestPart("files") Flux<FilePart> files,
            @RequestParam(defaultValue = "false") boolean overwrite) {
        return files.concatMap(part -> bytes(part).map(body -> new SkillUpload(part.filename(), body)))
                .collectList()
                .flatMap(list -> Mono.fromCallable(() -> skills.saveFiles(list, overwrite))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(SkillController::detail);
    }

    @GetMapping
    public Mono<SkillPageResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Mono.fromCallable(() -> page(skills.list(page, size)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{name}")
    public Mono<SkillDetailResponse> detail(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean includeBodies) {
        return Mono.fromCallable(() -> detail(skills.detail(name, includeBodies)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{name}")
    public Mono<Void> delete(@PathVariable String name) {
        return Mono.fromRunnable(() -> skills.delete(name))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
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
                page.items().stream().map(item -> new SkillSummaryResponse(
                        item.name(), item.description(), item.createdAt(), item.updatedAt(), item.resourceCount()))
                        .toList(),
                page.total(), page.page(), page.size());
    }

    private static SkillDetailResponse detail(SkillView view) {
        return new SkillDetailResponse(
                view.name(),
                view.description(),
                view.source(),
                view.createdAt(),
                view.updatedAt(),
                view.skillContent(),
                view.resources().stream()
                        .map(file -> new SkillResourceResponse(
                                file.path(), file.kind().name(), file.content()))
                        .toList());
    }
}
