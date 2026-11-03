package com.beyondzy.demo.service.user.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.mapper.user.UserMapper;
import com.beyondzy.demo.service.user.UserService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户服务实现，负责注册校验、密码哈希及数据库访问。 */
@Service
public class UserServiceImpl implements UserService {
    private static final String PHONE_PATTERN = "^\\+?[0-9]{7,20}$";
    private final UserMapper mapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserServiceImpl(UserMapper mapper) { this.mapper = mapper; }

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
}
