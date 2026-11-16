package com.beyondzy.demo.web.auth.res;

import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.support.DbTimes;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/** 登录和当前用户响应。未设置的过期时间与 token 不写出。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthUserResponse(Long userId, String name, String phone, Long expiresAt, String token, boolean admin) {
    public static AuthUserResponse of(UserBean user, LocalDateTime expiresAt, String rawToken, boolean admin) {
        Long expires = expiresAt == null ? null : DbTimes.toEpochMilli(expiresAt);
        return new AuthUserResponse(user.getId(), user.getName(), user.getPhone(), expires, rawToken, admin);
    }
}
