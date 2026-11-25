package com.beyondzy.demo.domain.directory;

import lombok.Data;
import lombok.EqualsAndHashCode;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;

/** 用户通讯录实体，主键沿用满天星用户 ID。 */
@Schema(description = "用户通讯录实体，主键沿用满天星用户 ID。")
@TableName("app_directory_user")
@Data
@EqualsAndHashCode(callSuper = true)
public class DirectoryUserBean extends BaseAuditBean {
    public static final String STATUS_ENABLED = "0";
    public static final String STATUS_DISABLED = "1";
    public static final int NOT_DELETED = 0;
    public static final int DELETED = 1;

    @Schema(description = "来源用户 ID")
    @TableId(type = IdType.INPUT)
    private Long sourceId;
    @Schema(description = "已处理的最新来源版本")
    private Long sourceVersion;
    @Schema(description = "所属部门的来源 ID")
    private Long departmentId;
    @Schema(description = "来源账号")
    private String account;
    @Schema(description = "成员显示名称")
    private String name;
    @Schema(description = "成员邮箱")
    private String email;
    @Schema(description = "来源账号状态：0 启用 / 1 停用")
    private String status;
    @Schema(description = "软删除标记：0 未删 / 1 已删")
    private Integer isDeleted;
}
