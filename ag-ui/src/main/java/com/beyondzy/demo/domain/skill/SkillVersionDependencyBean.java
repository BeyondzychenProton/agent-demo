package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;

/** 版本内一条源依赖的映射。目标快照只用于解释，发布时仍要核对真实目录。 */
@Schema(description = "版本内一条源依赖的映射。目标快照只用于解释，发布时仍要核对真实目录。")
@TableName("ai_skill_version_dependency")
public class SkillVersionDependencyBean extends BaseAuditBean {
    public static final String OPTIONAL = "0";
    public static final String REQUIRED = "1";

    @Schema(description = "版本依赖主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "所属 ai_skill_version.id")
    @TableField("version_id")
    private Long versionId;
    @Schema(description = "原包中的工具或依赖标识，版本内唯一")
    @TableField("source_key")
    private String sourceKey;
    @Schema(description = "依赖要求的能力标识，例如 meeting.write")
    @TableField("capability_key")
    private String capabilityKey;
    @Schema(description = "依赖要求：0 可选 / 1 必需")
    @TableField("required_flag")
    private String requiredFlag;
    @Schema(description = "MCP_TOOL MCP工具 / PLATFORM 平台能力 / UNRESOLVED 未解析")
    @TableField("target_kind")
    private String targetKind;
    @Schema(description = "目标 MCP 服务稳定业务键，平台能力或未解析时为空")
    @TableField("target_provider_key")
    private String targetProviderKey;
    @Schema(description = "目标工具或平台能力稳定业务键，未解析时为空")
    @TableField("target_tool_key")
    private String targetToolKey;
    @Schema(description = "SUGGESTED 建议映射 / CONFIRMED 已确认 / PENDING 待接入")
    @TableField("mapping_state")
    private String mappingState;
    @Schema(description = "映射说明或待接入原因，不保存认证凭证")
    private String reason;
    @Schema(description = "目标展示及配置摘要快照，不含令牌等认证秘密")
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
