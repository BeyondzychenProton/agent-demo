package com.beyondzy.demo.service.user;

import com.beyondzy.demo.domain.user.UserBean;

/** 用户注册与查询应用服务。对应表族：app_user。 */
public interface UserService {
    /** 注册新用户并返回持久化后的实体。 */
    UserBean register(String name, String phone, String rawPassword);

    /** 按手机号查询用户；不存在时返回 null。 */
    UserBean findByPhone(String phone);
}
