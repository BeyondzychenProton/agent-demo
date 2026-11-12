package com.beyondzy.demo.service.auth;

import com.beyondzy.demo.domain.user.UserBean;

/** 登录、注销与当前用户查询。 */
public interface AuthService {
    record LoginResult(UserBean user, AuthSessionService.CreatedSession session) {}

    LoginResult login(String phone, String password);

    void logout(String rawToken);

    UserBean requireUser(long userId);
}
