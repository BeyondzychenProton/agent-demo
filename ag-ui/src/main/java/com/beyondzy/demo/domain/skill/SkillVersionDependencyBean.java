package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;

/** 版本内一条源依赖的映射。目标快照只用于解释，发布时仍要核对真实目录。 */
@TableName("ai_skill_version_dependency")
public class SkillVersionDependencyBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("version_id")
    private Long versionId;
    @TableField("source_key")
    private String sourceKey;
    @TableField("capability_key")
    private String capabilityKey;
    @TableField("required_flag")
    private String requiredFlag;
    @TableField("target_kind")
    private String targetKind;
    @TableField("target_provider_key")
    private String targetProviderKey;
    @TableField("target_tool_key")
    private String targetToolKey;
    @TableField("mapping_state")
    private String mappingState;
    private String reason;
    @TableField("target_snapshot_json")
    private String targetSnapshotJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }
    public String getCapabilityKey() { return capabilityKey; }
    public void setCapabilityKey(String capabilityKey) { this.capabilityKey = capabilityKey; }
    public String getRequiredFlag() { return requiredFlag; }
    public void setRequiredFlag(String requiredFlag) { this.requiredFlag = requiredFlag; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String targetKind) { this.targetKind = targetKind; }
    public String getTargetProviderKey() { return targetProviderKey; }
    public void setTargetProviderKey(String targetProviderKey) { this.targetProviderKey = targetProviderKey; }
    public String getTargetToolKey() { return targetToolKey; }
    public void setTargetToolKey(String targetToolKey) { this.targetToolKey = targetToolKey; }
    public String getMappingState() { return mappingState; }
    public void setMappingState(String mappingState) { this.mappingState = mappingState; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getTargetSnapshotJson() { return targetSnapshotJson; }
    public void setTargetSnapshotJson(String targetSnapshotJson) { this.targetSnapshotJson = targetSnapshotJson; }
}
