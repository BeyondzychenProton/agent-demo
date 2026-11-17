package com.beyondzy.demo.service.user;

import com.beyondzy.demo.domain.user.UserBean;

/** 用户查询与密码校验。对应表族：app_user。 */
public interface UserService {
    String SYNC_INITIAL_PASSWORD = "nonProdPassword";

    /** 注册新用户并返回持久化后的实体。 */
    UserBean register(String name, String phone, String rawPassword);

    /** 按手机号查询用户；不存在时返回 null。 */
    UserBean findByPhone(String phone);

    /** 按主键查询；不存在时返回 null。 */
    UserBean findById(long id);

    /** 校验明文密码是否匹配用户哈希。 */
    boolean matchesPassword(UserBean user, String rawPassword);

    void changePassword(long userId, long sessionId, String currentPassword, String newPassword);
}
