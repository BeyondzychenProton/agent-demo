package com.beyondzy.demo.service;

import com.beyondzy.demo.domain.UserBean;

/** 用户注册与查询服务。 */
public interface UserService {
    /** 注册用户，并在写入数据库前对密码进行 BCrypt 哈希。 */
    UserBean register(String name, String phone, String rawPassword);

    /** 根据手机号查询用户；返回对象中的密码字段为哈希值。 */
    UserBean findByPhone(String phone);
}
