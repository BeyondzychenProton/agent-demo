package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import java.time.LocalDateTime;

/** 技能草稿或已发布快照。草稿不写运行表；已发布内容不能原地改。 */
@TableName("ai_skill_version")
public class SkillVersionBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("skill_name")
    private String skillName;
    @TableField("skill_id")
    private Long skillId;
    @TableField("version_no")
    private Integer versionNo;
    @TableField("package_version")
    private String packageVersion;
    @TableField("platform_version")
    private String platformVersion;
    private String state;
    private String description;
    private String displayName;
    @TableField("skill_content")
    private String skillContent;
    @TableField("metadata_json")
    private String metadataJson;
    @TableField("source_rules_json")
    private String sourceRulesJson;
    @TableField("visible_scope")
    private String visibleScope;
    @TableField("owner_dept_id")
    private Long ownerDeptId;
    @TableField("created_by")
    private Long createdBy;
    @TableField("import_id")
    private Long importId;
    @TableField("published_by")
    private Long publishedBy;
    @TableField("published_at")
    private LocalDateTime publishedAt;
    private Long revision;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getPackageVersion() { return packageVersion; }
    public void setPackageVersion(String packageVersion) { this.packageVersion = packageVersion; }
    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSkillContent() { return skillContent; }
    public void setSkillContent(String skillContent) { this.skillContent = skillContent; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getSourceRulesJson() { return sourceRulesJson; }
    public void setSourceRulesJson(String sourceRulesJson) { this.sourceRulesJson = sourceRulesJson; }
    public String getVisibleScope() { return visibleScope; }
    public void setVisibleScope(String visibleScope) { this.visibleScope = visibleScope; }
    public Long getOwnerDeptId() { return ownerDeptId; }
    public void setOwnerDeptId(Long ownerDeptId) { this.ownerDeptId = ownerDeptId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getImportId() { return importId; }
    public void setImportId(Long importId) { this.importId = importId; }
    public Long getPublishedBy() { return publishedBy; }
    public void setPublishedBy(Long publishedBy) { this.publishedBy = publishedBy; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
}
