package com.beyondzy.demo.web.skill;

import com.beyondzy.demo.service.skill.SkillResourceCatalog;
import com.beyondzy.demo.service.user.UserService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 导入时可映射的真实工具和平台能力。发现失败返回 503，不补模拟数据。 */
@RestController
@RequestMapping(value = "/api/admin/skill-resource-options", produces = MediaType.APPLICATION_JSON_VALUE)
public class SkillResourceController {
    private final SkillResourceCatalog catalog;
    private final UserService users;

    public SkillResourceController(SkillResourceCatalog catalog, UserService users) {
        this.catalog = catalog;
        this.users = users;
    }

    @GetMapping
    public Mono<SkillResourceCatalog.SkillResourcePage> list(
            @RequestParam String kind,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return SkillAdminAccess.requireAdmin(users)
                .flatMap(id -> Mono.fromCallable(() -> catalog.page(kind, query, page, size))
                        .subscribeOn(Schedulers.boundedElastic()));
    }
}
