package com.beyondzy.demo.service.user.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.mapper.auth.AuthSessionMapper;
import com.beyondzy.demo.mapper.user.UserMapper;
import com.beyondzy.demo.service.user.UserService;
import com.beyondzy.demo.support.DbTimes;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DuplicateKeyException;
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
        var user = mapper.selectOne(Wrappers.<UserBean>lambdaQuery()
                .apply("phone_active = {0}", phone)
                .last("FOR UPDATE"));
        return initializePasswordChange(user);
    }

    @Override
    public UserBean findById(long id) {
        UserBean user = mapper.selectById(id);
        user = initializePasswordChange(user);
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

    @Override
    @Transactional
    public void syncDirectoryAccount(long sourceId, String name, String phone, boolean active) {
        var account = findDirectoryAccount(sourceId);
        try {
            if (account == null) {
                account = new UserBean();
                account.setMantianxingUserId(sourceId);
                account.setName(name);
                account.setPhone(phone);
                account.setPasswordHash(passwordEncoder.encode(SYNC_INITIAL_PASSWORD));
                account.setMustChangePassword(1);
                account.setIsDeleted(active ? 0 : 1);
                mapper.insert(account);
            } else {
                mapper.update(null, Wrappers.<UserBean>lambdaUpdate()
                        .eq(UserBean::getId, account.getId())
                        .set(UserBean::getName, name)
                        .set(UserBean::getPhone, phone)
                        .set(UserBean::getIsDeleted, active ? 0 : 1)
                        .set(UserBean::getGmtModified, DbTimes.now()));
            }
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already belongs to another account", ex);
        }
        if (!active) revokeSessions(account.getId());
    }

    @Override
    @Transactional
    public void disableDirectoryAccount(long sourceId) {
        var account = findDirectoryAccount(sourceId);
        if (account == null) return;
        mapper.update(null, Wrappers.<UserBean>lambdaUpdate()
                .eq(UserBean::getId, account.getId())
                .set(UserBean::getIsDeleted, 1)
                .set(UserBean::getGmtModified, DbTimes.now()));
        revokeSessions(account.getId());
    }

    private UserBean findDirectoryAccount(long sourceId) {
        return mapper.selectOne(Wrappers.<UserBean>lambdaQuery()
                .select(UserBean::getId, UserBean::getPhone)
                .eq(UserBean::getMantianxingUserId, sourceId));
    }

    private void revokeSessions(long userId) {
        sessions.delete(Wrappers.<AuthSessionBean>lambdaQuery().eq(AuthSessionBean::getUserId, userId));
    }

    /** 历史同步账号在认证时补齐标记，保留并发完成的改密结果。 */
    private UserBean initializePasswordChange(UserBean user) {
        if (user == null || user.getMantianxingUserId() == null || user.getMustChangePassword() != null
                || Integer.valueOf(1).equals(user.getIsDeleted())) return user;

        int required = matchesPassword(user, SYNC_INITIAL_PASSWORD) ? 1 : 0;
        int updated = mapper.update(null, Wrappers.<UserBean>lambdaUpdate()
                .eq(UserBean::getId, user.getId())
                .eq(UserBean::getIsDeleted, 0)
                .isNull(UserBean::getMustChangePassword)
                .eq(UserBean::getPasswordHash, user.getPasswordHash())
                .set(UserBean::getMustChangePassword, required));
        if (updated == 0) return mapper.selectById(user.getId());

        user.setMustChangePassword(required);
        return user;
    }
}
