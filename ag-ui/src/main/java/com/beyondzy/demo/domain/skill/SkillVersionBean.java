package com.beyondzy.demo.domain.skill;

import lombok.Data;
import lombok.EqualsAndHashCode;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 技能草稿或已发布快照。草稿不写运行表；已发布内容不能原地改。 */
@Schema(description = "技能草稿或已发布快照。草稿不写运行表；已发布内容不能原地改。")
@TableName("ai_skill_version")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillVersionBean extends BaseAuditBean {
    @Schema(description = "技能版本主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "技能逻辑名称，版本间保持稳定")
    @TableField("skill_name")
    private String skillName;
    @Schema(description = "agentscope_skills.id，新技能首次发布前可为空")
    @TableField("skill_id")
    private Long skillId;
    @Schema(description = "技能范围内内部版本序号，从 1 开始")
    @TableField("version_no")
    private Integer versionNo;
    @Schema(description = "原包声明版本，原包未声明时为空")
    @TableField("package_version")
    private String packageVersion;
    @Schema(description = "平台发布版本号，未分配时为空")
    @TableField("platform_version")
    private String platformVersion;
    @Schema(description = "DRAFT 草稿 / PUBLISHED 已发布 / ARCHIVED 已归档；与技能启停状态独立")
    private String state;
    @Schema(description = "该版本的技能用途说明")
    private String description;
    @Schema(description = "版本展示名称，与唯一技能标识分离")
    private String displayName;
    @Schema(description = "该版本拟发布的完整 SKILL.md，包含 frontmatter")
    @TableField("skill_content")
    private String skillContent;
    @Schema(description = "该版本的技能元数据快照")
    @TableField("metadata_json")
    private String metadataJson;
    @Schema(description = "原包约束规则快照，供导入检查及发布重新核验")
    @TableField("source_rules_json")
    private String sourceRulesJson;
    @Schema(description = "发布可见范围：0 公开 / 1 本部门 / 2 仅本人")
    @TableField("visible_scope")
    private String visibleScope;
    @Schema(description = "app_directory_department.source_id，仅本部门可见时使用")
    @TableField("owner_dept_id")
    private Long ownerDeptId;
    @Schema(description = "版本创建人 app_user.id；仅本人范围的归属人")
    @TableField("created_by")
    private Long createdBy;
    @Schema(description = "来源 ai_skill_import.id，手工或存量基线版本可为空")
    @TableField("import_id")
    private Long importId;
    @Schema(description = "执行发布的 app_user.id，未发布时为空")
    @TableField("published_by")
    private Long publishedBy;
    @Schema(description = "发布时间，未发布时为空")
    @TableField("published_at")
    private LocalDateTime publishedAt;
    @Schema(description = "乐观锁版本号，草稿更新时条件比较并递增")
    private Long revision;
}
