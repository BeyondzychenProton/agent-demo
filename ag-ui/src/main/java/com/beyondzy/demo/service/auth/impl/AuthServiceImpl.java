package com.beyondzy.demo.service.auth.impl;

import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.service.auth.AuthService;
import com.beyondzy.demo.service.auth.AuthService.LoginResult;
import com.beyondzy.demo.service.auth.AuthSessionService;
import com.beyondzy.demo.service.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 登录、注销与当前用户查询。 */
@Service
public class AuthServiceImpl implements AuthService {
    private final UserService users;
    private final AuthSessionService sessions;

    public AuthServiceImpl(UserService users, AuthSessionService sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @Override
    public LoginResult login(String phone, String password) {
        if (phone == null || phone.isBlank() || password == null || password.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号和密码不能为空");
        UserBean user = users.findByPhone(phone.strip());
        if (user == null || !users.matchesPassword(user, password))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "手机号或密码错误");
        return new LoginResult(user, sessions.create(user.getId()));
    }

    @Override
    public void logout(String rawToken) {
        sessions.deleteByRawToken(rawToken);
    }

    @Override
    public UserBean requireUser(long userId) {
        UserBean user = users.findById(userId);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在");
        return user;
    }
}
