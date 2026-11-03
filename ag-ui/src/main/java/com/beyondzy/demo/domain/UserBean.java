package com.beyondzy.demo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 用户表持久化对象；密码字段保存 BCrypt 哈希值，不保存明文密码。 */
@TableName("app_user")
public class UserBean extends BaseAuditBean {
    /** 用户主键，由数据库自增生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户姓名。 */
    private String name;

    /** 用户手机号，数据库中设置唯一约束。 */
    private String phone;

    /** 用户密码的 BCrypt 哈希值。 */
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
