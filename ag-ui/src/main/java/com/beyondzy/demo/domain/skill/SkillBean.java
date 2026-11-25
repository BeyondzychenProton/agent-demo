package com.beyondzy.demo.domain.skill;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 全局技能行。对应表：agentscope_skills。
 *
 * <p>时间列是框架的 created_at/updated_at，不走审计填充，也不做逻辑删除。
 */
@Schema(description = "全局技能行。对应表：agentscope_skills。")
@TableName("agentscope_skills")
@Data
public class SkillBean {
    public static final String STATUS_ENABLED = "0";
    public static final String STATUS_DISABLED = "1";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    @TableField("skill_content")
    private String skillContent;
    private String source;
    @TableField("metadata_json")
    private String metadataJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    @Schema(description = "0 启用，1 停用。与通讯录状态同一套取值。")
    private String status;
    @Schema(description = "0 公开，1 本部门，2 仅本人。")
    @TableField("visible_scope")
    private String visibleScope;
    @Schema(description = "只在 visible_scope=1 时和当前用户的部门 id 比较。")
    @TableField("owner_dept_id")
    private Long ownerDeptId;
    @Schema(description = "新写入必填。只在 visible_scope=2 时和 app_user.id 比较。")
    @TableField("create_by")
    private Long createBy;
    @Schema(description = "列表查询的资源行数，不是表字段。")
    @TableField(exist = false)
    private Integer resourceCount;
    @Schema(description = "列表带出的部门名，不是表字段。")
    @TableField(exist = false)
    private String ownerDeptName;
    @Schema(description = "是否绑在 session-chat 上，不是表字段。")
    @TableField(exist = false)
    private Integer bound;
    @Schema(description = "固定的已发布技能版本；存量迁移后填充")
    @TableField(exist = false)
    private Long versionId;
}
