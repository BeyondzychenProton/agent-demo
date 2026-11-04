package com.beyondzy.demo.auth.service;

import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.user.UserService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 登录、注销与当前用户查询。 */
@Service
public class AuthService {
    private final UserService users;
    private final AuthSessionService sessions;

    public AuthService(UserService users, AuthSessionService sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    public record LoginResult(UserBean user, AuthSessionService.CreatedSession session) {}

    public LoginResult login(String phone, String password) {
        if (phone == null || phone.isBlank() || password == null || password.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号和密码不能为空");
        UserBean user = users.findByPhone(phone.strip());
        if (user == null || !users.matchesPassword(user, password))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "手机号或密码错误");
        return new LoginResult(user, sessions.create(user.getId()));
    }

    public void logout(String rawToken) {
        sessions.deleteByRawToken(rawToken);
    }

    public Map<String, Object> toUserBody(UserBean user, Long expiresAt, String rawToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.getId());
        body.put("name", user.getName());
        body.put("phone", user.getPhone());
        if (expiresAt != null) body.put("expiresAt", expiresAt);
        if (rawToken != null) body.put("token", rawToken);
        return body;
    }

    public UserBean requireUser(long userId) {
        UserBean user = users.findById(userId);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在");
        return user;
    }
}
