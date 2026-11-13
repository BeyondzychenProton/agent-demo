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
    /** 归属用户；0 = 平台公共。唯一键是 (owner_user_id, name)。 */
    @TableField("owner_user_id")
    private Long ownerUserId;
    @TableField("metadata_json")
    private String metadataJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    /** 列表查询的资源行数，不是表字段。 */
    @TableField(exist = false)
    private Integer resourceCount;

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
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getResourceCount() { return resourceCount; }
    public void setResourceCount(Integer resourceCount) { this.resourceCount = resourceCount; }
}
