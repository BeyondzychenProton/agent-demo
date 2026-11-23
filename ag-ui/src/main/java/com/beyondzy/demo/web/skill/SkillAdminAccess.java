package com.beyondzy.demo.web.skill;

import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.handler.admin.CurrentUser;
import com.beyondzy.demo.service.user.UserService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 管理端技能接口的鉴权，和通讯录查询一样要求 app_user.is_admin=1。 */
final class SkillAdminAccess {
    private SkillAdminAccess() {}

    static Mono<Long> requireAdmin(UserService users) {
        return CurrentUser.require().flatMap(principal -> Mono.fromCallable(() -> {
            var user = users.findById(principal.userId());
            if (user == null || !user.isAdministrator()) {
                throw new SkillException(403, "Admin access required");
            }
            return principal.userId();
        }).subscribeOn(Schedulers.boundedElastic()));
    }
}
