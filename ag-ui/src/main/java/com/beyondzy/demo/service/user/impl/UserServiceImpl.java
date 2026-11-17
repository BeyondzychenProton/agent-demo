package com.beyondzy.demo.service.user.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.mapper.auth.AuthSessionMapper;
import com.beyondzy.demo.mapper.user.UserMapper;
import com.beyondzy.demo.service.user.UserService;

import com.beyondzy.demo.support.DbTimes;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 用户服务实现，负责注册校验、密码哈希及数据库访问。 */
@Service
public class UserServiceImpl implements UserService {
    private static final String PHONE_PATTERN = "^\\+?[0-9]{7,20}$";
    private final UserMapper mapper;
    private final AuthSessionMapper sessions;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserServiceImpl(UserMapper mapper, AuthSessionMapper sessions) {
        this.mapper = mapper;
        this.sessions = sessions;
    }

    @Override
    @Transactional
    public UserBean register(String name, String phone, String rawPassword) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("姓名不能为空");
        if (phone == null || !phone.matches(PHONE_PATTERN)) throw new IllegalArgumentException("手机号格式不正确");
        if (rawPassword == null || rawPassword.length() < 8) throw new IllegalArgumentException("密码至少需要 8 位");
        if (findByPhone(phone) != null) throw new IllegalArgumentException("手机号已注册");

        UserBean user = new UserBean();
        user.setName(name.strip());
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        mapper.insert(user);
        return user;
    }

    @Override
    public UserBean findByPhone(String phone) {
        return mapper.selectOne(Wrappers.<UserBean>lambdaQuery()
                .apply("phone_active = {0}", phone)
                .last("FOR UPDATE"));
    }

    @Override
    public UserBean findById(long id) {
        UserBean user = mapper.selectById(id);
        if (user == null || (user.getIsDeleted() != null && user.getIsDeleted() != 0)) {
            return null;
        }
        return user;
    }

    @Override
    public boolean matchesPassword(UserBean user, String rawPassword) {
        return user != null && rawPassword != null
                && passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    @Override
    @Transactional
    public void changePassword(long userId, long sessionId, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.isBlank() || newPassword.length() < 8
                || newPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || newPassword.equals(currentPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid new password");
        }
        UserBean user = findById(userId);
        if (currentPassword == null || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !matchesPassword(user, currentPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect current password");
        }
        if (user.getMantianxingUserId() != null && SYNC_INITIAL_PASSWORD.equals(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Initial password cannot be reused");
        }
        int changed = mapper.update(null, Wrappers.<UserBean>lambdaUpdate()
                .eq(UserBean::getId, userId)
                .eq(UserBean::getIsDeleted, 0)
                .eq(UserBean::getPasswordHash, user.getPasswordHash())
                .set(UserBean::getPasswordHash, passwordEncoder.encode(newPassword))
                .set(UserBean::getMustChangePassword, 0)
                .set(UserBean::getGmtModified, DbTimes.now()));
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account changed, please retry");
        }
        sessions.delete(Wrappers.<AuthSessionBean>lambdaQuery()
                .eq(AuthSessionBean::getUserId, userId)
                .ne(AuthSessionBean::getId, sessionId));
    }
}
