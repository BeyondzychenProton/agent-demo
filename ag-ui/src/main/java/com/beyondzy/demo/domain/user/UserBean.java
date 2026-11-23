package com.beyondzy.demo.domain.user;

import com.beyondzy.demo.domain.BaseAuditBean;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    private Integer isDeleted;
    private Long mantianxingUserId;
    private Integer mustChangePassword;
    @TableField("is_admin")
    private Integer isAdmin;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
    public Long getMantianxingUserId() { return mantianxingUserId; }
    public void setMantianxingUserId(Long mantianxingUserId) { this.mantianxingUserId = mantianxingUserId; }
    public Integer getMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(Integer mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public Integer getIsAdmin() { return isAdmin; }
    public void setIsAdmin(Integer isAdmin) { this.isAdmin = isAdmin; }

    /** 以 app_user.is_admin 为准；未查出该列时视为非管理员。 */
    public boolean isAdministrator() {
        return isAdmin != null && isAdmin == 1;
    }

    public boolean isPasswordChangeRequired() {
        return mustChangePassword == null ? mantianxingUserId != null : mustChangePassword == 1;
    }
}
