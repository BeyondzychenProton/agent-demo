package com.beyondzy.demo.web.skill;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.beyondzy.demo.service.skill.SkillAdminService;
import com.beyondzy.demo.service.skill.view.SkillAdminView;
import com.beyondzy.demo.service.user.UserService;
import com.beyondzy.demo.web.skill.req.SaveAgentSkillsRequest;
import com.beyondzy.demo.web.skill.res.AgentSkillResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 维护 session-chat 的技能绑定。其他 agentId 由服务返回 404。 */
@Tag(name = "技能绑定", description = "维护 session-chat 的技能绑定")
@RestController
@RequestMapping(value = "/api/admin/agents", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentSkillAdminController {
    private final SkillAdminService skills;
    private final UserService users;

    public AgentSkillAdminController(SkillAdminService skills, UserService users) {
        this.skills = skills;
        this.users = users;
    }

    @GetMapping("/{agentId}")
    @Operation(summary = "查询智能体绑定")
    public Mono<AgentSkillResponse> agent(@PathVariable String agentId) {
        return SkillAdminAccess.requireAdmin(users)
                .flatMap(id -> Mono.fromCallable(() -> response(skills.agent(agentId)))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @PutMapping("/{agentId}/skills")
    @Operation(summary = "覆盖智能体的技能绑定")
    public Mono<Void> replace(@PathVariable String agentId, @RequestBody SaveAgentSkillsRequest request) {
        return SkillAdminAccess.requireAdmin(users)
                .flatMap(id -> Mono.<Void>fromRunnable(() -> {
                    if (request.bindings() == null) skills.replaceBindings(id, agentId, request.names());
                    else skills.replaceVersionBindings(id, agentId, request.bindings().stream()
                            .map(b -> new SkillAdminService.Binding(b.name(), b.versionId())).toList());
                })
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private static AgentSkillResponse response(SkillAdminView.Agent agent) {
        return new AgentSkillResponse(
                agent.agentId(),
                agent.title(),
                agent.skills().stream()
                        .map(skill -> new AgentSkillResponse.BoundSkill(
                                skill.name(), skill.description(), skill.status(), skill.visibleScope(),
                                skill.versionId(), skill.platformVersion(), skill.versionNo()))
                        .toList());
    }
}
