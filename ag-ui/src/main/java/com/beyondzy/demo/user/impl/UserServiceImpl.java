package com.beyondzy.demo.user.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.mapper.user.UserMapper;
import com.beyondzy.demo.user.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户服务实现，负责注册校验、密码哈希及数据库访问。 */
@Service
public class UserServiceImpl implements UserService {
    private static final String PHONE_PATTERN = "^\\+?[0-9]{7,20}$";
    private final UserMapper mapper;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserServiceImpl(UserMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
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
        return mapper.selectOne(Wrappers.<UserBean>lambdaQuery().eq(UserBean::getPhone, phone));
    }

    @Override
    public UserBean findById(long id) {
        return mapper.selectById(id);
    }

    @Override
    public boolean matchesPassword(UserBean user, String rawPassword) {
        return user != null && rawPassword != null
                && passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    @Override
    @Transactional
    public UserBean ensureSeedUser(long preferredId, String name, String phone, String rawPassword) {
        UserBean byPhone = findByPhone(phone);
        if (byPhone != null) {
            if (!matchesPassword(byPhone, rawPassword)) {
                byPhone.setPasswordHash(passwordEncoder.encode(rawPassword));
                byPhone.setName(name);
                mapper.updateById(byPhone);
            }
            return byPhone;
        }
        UserBean byId = mapper.selectById(preferredId);
        if (byId != null) {
            byId.setName(name);
            byId.setPhone(phone);
            byId.setPasswordHash(passwordEncoder.encode(rawPassword));
            mapper.updateById(byId);
            return byId;
        }
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO app_user(id, name, phone, password_hash, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                preferredId, name, phone, passwordEncoder.encode(rawPassword), now, now);
        return mapper.selectById(preferredId);
    }
}
