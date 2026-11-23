package com.beyondzy.demo.web.skill;

import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.web.skill.res.SkillDetailResponse;
import com.beyondzy.demo.web.skill.res.SkillPageResponse;
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

/**
 * 旧的技能读写入口。管理端改走 /api/admin/skills 之后，这里一律返回 410。
 *
 * <p>GET 也停掉，是因为它会把未绑定和仅本人的技能列给任意登录用户。
 */
@RestController
@RequestMapping(value = "/api/skills", produces = MediaType.APPLICATION_JSON_VALUE)
public class SkillController {
    private static final String MOVED = "写入口已收到管理端";
    private static final String QUERY_MOVED = "技能查询已收到管理端";

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SkillDetailResponse> uploadFile(
            @RequestPart("file") FilePart file,
            @RequestParam(defaultValue = "false") boolean overwrite) {
        return retiredWrite();
    }

    @PostMapping(value = "/archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SkillDetailResponse> uploadArchive(
            @RequestPart("file") FilePart file,
            @RequestParam(defaultValue = "false") boolean overwrite) {
        return retiredWrite();
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SkillDetailResponse> uploadFiles(
            @RequestPart("files") Flux<FilePart> files,
            @RequestParam(defaultValue = "false") boolean overwrite) {
        return retiredWrite();
    }

    @GetMapping
    public Mono<SkillPageResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return retiredQuery();
    }

    @GetMapping("/{name}")
    public Mono<SkillDetailResponse> detail(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean includeBodies) {
        return retiredQuery();
    }

    @DeleteMapping("/{name}")
    public Mono<Void> delete(@PathVariable String name) {
        return retiredWrite();
    }

    private static <T> Mono<T> retiredWrite() {
        return Mono.error(new SkillException(410, MOVED));
    }

    private static <T> Mono<T> retiredQuery() {
        return Mono.error(new SkillException(410, QUERY_MOVED));
    }
}
