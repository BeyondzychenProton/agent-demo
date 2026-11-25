package com.beyondzy.demo.domain.user;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import com.beyondzy.demo.domain.BaseAuditBean;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/** 用户表持久化模型；密码字段仅保存 BCrypt 哈希。对应表：app_user。 */
@Schema(description = "用户表持久化模型；密码字段仅保存 BCrypt 哈希。对应表：app_user。")
@TableName("app_user")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserBean extends BaseAuditBean {
    public static final int NOT_DELETED = 0;
    public static final int DELETED = 1;
    public static final int PASSWORD_CURRENT = 0;
    public static final int PASSWORD_MUST_CHANGE = 1;
    public static final int ADMINISTRATOR = 1;

    @Schema(description = "用户主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "用户姓名")
    private String name;
    @Schema(description = "用户手机号")
    private String phone;
    @Schema(description = "BCrypt 密码哈希，不存储明文")
    @ToString.Exclude
    private String passwordHash;
    @Schema(description = "软删除标记：0 未删 / 1 已删")
    private Integer isDeleted;
    @Schema(description = "满天星来源用户 ID")
    private Long mantianxingUserId;
    @Schema(description = "首次登录改密标记：0 已完成 / 1 必须改密；NULL 为历史记录待初始化")
    private Integer mustChangePassword;
    @Schema(description = "平台管理员：0 否 / 1 是")
    @TableField("is_admin")
    private Integer isAdmin;

    /** 以 app_user.is_admin 为准；未查出该列时视为非管理员。 */
    public boolean isAdministrator() {
        return Objects.equals(isAdmin, ADMINISTRATOR);
    }

    public boolean isPasswordChangeRequired() {
        return mustChangePassword == null ? mantianxingUserId != null : Objects.equals(mustChangePassword, PASSWORD_MUST_CHANGE);
    }
}
