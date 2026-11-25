package com.beyondzy.demo.domain.mcp;

import com.baomidou.mybatisplus.annotation.*;
import com.beyondzy.demo.domain.BaseAuditBean;
import java.time.LocalDateTime;

@TableName("ai_mcp_service")
public class McpServiceBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String providerKey;
    private String displayName;
    private String description;
    private String ownerName;
    private Integer enabled;
    private Long controlVersion;
    private Integer catalogInitialized;
    private Long catalogRevision;
    private Long checkRevision;
    private String lastCheckState;
    private LocalDateTime lastCheckStartedAt;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime lastSuccessAt;
    private Integer lastLatencyMs;
    private String lastErrorCode;
    private String lastErrorMessage;
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
