package com.beyondzy.demo.domain.mcp;

import com.baomidou.mybatisplus.annotation.*;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 代码接入的 MCP 服务的展示信息、总开关和最近一次连接检测。 */
@Schema(description = "代码接入的 MCP 服务的展示信息、总开关和最近一次连接检测。")
@TableName("ai_mcp_service")
public class McpServiceBean extends BaseAuditBean {
    @Schema(description = "服务主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "代码接入的服务键，唯一且区分大小写")
    private String providerKey;
    @Schema(description = "管理页展示名称，可由保存信息修改")
    private String displayName;
    @Schema(description = "管理说明，可由保存信息修改")
    private String description;
    @Schema(description = "负责人展示名称，可由保存信息修改")
    private String ownerName;
    @Schema(description = "服务总开关：1 启用，0 停用")
    private Integer enabled;
    @Schema(description = "人工修改展示信息或服务开关时的乐观锁版本，检测不更新")
    private Long controlVersion;
    @Schema(description = "目录基线是否已建立：1 已建立，0 未建立")
    private Integer catalogInitialized;
    @Schema(description = "成功更新目录后递增的版本号")
    private Long catalogRevision;
    @Schema(description = "检测序号，仅当完成时仍等于开始序号才写回结果")
    private Long checkRevision;
    @Schema(description = "最近检测状态：UNKNOWN、CHECKING、HEALTHY、FAILED")
    private String lastCheckState;
    @Schema(description = "最近一次检测的开始时间")
    private LocalDateTime lastCheckStartedAt;
    @Schema(description = "最近一次已写回的检测完成时间")
    private LocalDateTime lastCheckedAt;
    @Schema(description = "最近一次成功发现并更新目录的时间")
    private LocalDateTime lastSuccessAt;
    @Schema(description = "最近一次已写回检测的耗时毫秒，失败也会记录")
    private Integer lastLatencyMs;
    @Schema(description = "最近一次已写回检测的错误码：UNAUTHENTICATED、SERVER_UNAVAILABLE、TRANSPORT_FAILURE、PROTOCOL、INVALID_CATALOG、DISCOVERY_TIMEOUT、DISCOVERY_INCOMPLETE 或 DISCOVERY_FAILED，成功时为空")
    private String lastErrorCode;
    @Schema(description = "按错误码写入的固定摘要，成功时为空，不保存远端原文")
    private String lastErrorMessage;
    @Schema(description = "最近一次人工修改的 app_user.id，检测不更新")
    private Long updatedBy;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String value) { providerKey = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String value) { ownerName = value; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer value) { enabled = value; }
    public Long getControlVersion() { return controlVersion; }
    public void setControlVersion(Long value) { controlVersion = value; }
    public Integer getCatalogInitialized() { return catalogInitialized; }
    public void setCatalogInitialized(Integer value) { catalogInitialized = value; }
    public Long getCatalogRevision() { return catalogRevision; }
    public void setCatalogRevision(Long value) { catalogRevision = value; }
    public Long getCheckRevision() { return checkRevision; }
    public void setCheckRevision(Long value) { checkRevision = value; }
    public String getLastCheckState() { return lastCheckState; }
    public void setLastCheckState(String value) { lastCheckState = value; }
    public LocalDateTime getLastCheckStartedAt() { return lastCheckStartedAt; }
    public void setLastCheckStartedAt(LocalDateTime value) { lastCheckStartedAt = value; }
    public LocalDateTime getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(LocalDateTime value) { lastCheckedAt = value; }
    public LocalDateTime getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(LocalDateTime value) { lastSuccessAt = value; }
    public Integer getLastLatencyMs() { return lastLatencyMs; }
    public void setLastLatencyMs(Integer value) { lastLatencyMs = value; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String value) { lastErrorCode = value; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String value) { lastErrorMessage = value; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long value) { updatedBy = value; }
}
