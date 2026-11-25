package com.beyondzy.demo.web.auth.res;

import io.swagger.v3.oas.annotations.media.Schema;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.support.DbTimes;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/** 登录和当前用户响应。未设置的过期时间与 token 不写出。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "登录和当前用户响应。未设置的过期时间与 token 不写出。")
public record AuthUserResponse(@Schema(description = "用户 id") Long userId, @Schema(description = "用户姓名") String name, @Schema(description = "手机号") String phone, @Schema(description = "会话过期时间，毫秒时间戳") Long expiresAt, @Schema(description = "登录令牌。仅登录响应返回") String token, @Schema(description = "是否平台管理员") boolean admin, @Schema(description = "是否必须先修改密码") boolean mustChangePassword) {
    public static AuthUserResponse of(UserBean user, LocalDateTime expiresAt, String rawToken, boolean admin) {
        Long expires = expiresAt == null ? null : DbTimes.toEpochMilli(expiresAt);
        return new AuthUserResponse(user.getId(), user.getName(), user.getPhone(), expires, rawToken, admin, user.isPasswordChangeRequired());
    }
}
