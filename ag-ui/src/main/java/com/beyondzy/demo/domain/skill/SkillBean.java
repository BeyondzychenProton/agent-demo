package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 全局技能行。对应表：agentscope_skills。
 *
 * <p>时间列是框架的 created_at/updated_at，不走审计填充，也不做逻辑删除。
 */
@TableName("agentscope_skills")
public class SkillBean {
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
    /** 0 启用，1 停用。与通讯录状态同一套取值。 */
    private String status;
    /** 0 公开，1 本部门，2 仅本人。 */
    @TableField("visible_scope")
    private String visibleScope;
    /** 只在 visible_scope=1 时和当前用户的部门 id 比较。 */
    @TableField("owner_dept_id")
    private Long ownerDeptId;
    /** 新写入必填。只在 visible_scope=2 时和 app_user.id 比较。 */
    @TableField("create_by")
    private Long createBy;
    /** 列表查询的资源行数，不是表字段。 */
    @TableField(exist = false)
    private Integer resourceCount;
    /** 列表带出的部门名，不是表字段。 */
    @TableField(exist = false)
    private String ownerDeptName;
    /** 是否绑在 session-chat 上，不是表字段。 */
    @TableField(exist = false)
    private Integer bound;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSkillContent() { return skillContent; }
    public void setSkillContent(String skillContent) { this.skillContent = skillContent; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVisibleScope() { return visibleScope; }
    public void setVisibleScope(String visibleScope) { this.visibleScope = visibleScope; }
    public Long getOwnerDeptId() { return ownerDeptId; }
    public void setOwnerDeptId(Long ownerDeptId) { this.ownerDeptId = ownerDeptId; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public Integer getResourceCount() { return resourceCount; }
    public void setResourceCount(Integer resourceCount) { this.resourceCount = resourceCount; }
    public String getOwnerDeptName() { return ownerDeptName; }
    public void setOwnerDeptName(String ownerDeptName) { this.ownerDeptName = ownerDeptName; }
    public Integer getBound() { return bound; }
    public void setBound(Integer bound) { this.bound = bound; }
}
