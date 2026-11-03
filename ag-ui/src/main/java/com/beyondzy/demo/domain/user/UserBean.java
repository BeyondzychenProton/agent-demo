package com.beyondzy.demo.domain.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 用户表持久化模型；密码字段仅保存 BCrypt 哈希。对应表：app_user。 */
@TableName("app_user")
public class UserBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String phone;
    private String passwordHash;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
