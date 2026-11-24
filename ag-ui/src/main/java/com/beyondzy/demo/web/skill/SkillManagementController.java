package com.beyondzy.demo.web.skill;

import com.beyondzy.demo.service.skill.SkillManagementService;
import com.beyondzy.demo.service.skill.SkillVersionView;
import com.beyondzy.demo.service.skill.impl.SkillBaselineMigrator;
import com.beyondzy.demo.service.user.UserService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 管理目录按 Skill 聚合，版本 API 按具体版本维护内容。 */
@RestController
@RequestMapping("/api/admin/skills")
public class SkillManagementController {
    private final SkillManagementService management;
    private final SkillBaselineMigrator baselines;
    private final UserService users;
    public SkillManagementController(SkillManagementService management, SkillBaselineMigrator baselines, UserService users) {
        this.management = management; this.baselines = baselines; this.users = users;
    }

    @GetMapping("/catalog")
    public Mono<SkillManagementService.Page> list(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String state, @RequestParam(defaultValue = "all") String resource,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return SkillAdminAccess.requireAdmin(users).flatMap(actor -> Mono.fromCallable(() -> management.page(q, state, resource, page, size))
                .subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping("/{name}/overview")
    public Mono<SkillManagementService.Overview> overview(@PathVariable String name) {
        return SkillAdminAccess.requireAdmin(users).flatMap(actor -> Mono.fromCallable(() -> management.overview(name))
                .subscribeOn(Schedulers.boundedElastic()));
    }
    @PostMapping("/{name}/draft")
    public Mono<SkillVersionView> edit(@PathVariable String name) {
        return SkillAdminAccess.requireAdmin(users).flatMap(actor -> Mono.fromCallable(() -> management.edit(actor, name))
                .subscribeOn(Schedulers.boundedElastic()));
    }
    @PostMapping("/migrate-releases")
    public Mono<Map<String, Integer>> migrate() {
        return SkillAdminAccess.requireAdmin(users).flatMap(actor -> Mono.fromCallable(() -> Map.of("created", baselines.migrate(actor)))
                .subscribeOn(Schedulers.boundedElastic()));
    }
}
