package com.beyondzy.demo.domain.skill;

/** 管理列表聚合投影，不含技能正文或文件字节。 */
public class SkillManagementRow {
    private String name;
    private String displayName;
    private String description;
    private String status;
    private String visibleScope;
    private Long ownerId;
    private String ownerName;
    private java.time.LocalDateTime updatedAt;
    private Long currentVersionId;
    private String platformVersion;
    private Integer versionNo;
    private Long draftVersionId;
    private Integer draftVersionNo;
    private Integer dependencyCount;
    private Integer mappedCount;
    private Integer boundCount;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVisibleScope() { return visibleScope; }
    public void setVisibleScope(String visibleScope) { this.visibleScope = visibleScope; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(Long currentVersionId) { this.currentVersionId = currentVersionId; }
    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public Long getDraftVersionId() { return draftVersionId; }
    public void setDraftVersionId(Long draftVersionId) { this.draftVersionId = draftVersionId; }
    public Integer getDraftVersionNo() { return draftVersionNo; }
    public void setDraftVersionNo(Integer draftVersionNo) { this.draftVersionNo = draftVersionNo; }
    public Integer getDependencyCount() { return dependencyCount; }
    public void setDependencyCount(Integer dependencyCount) { this.dependencyCount = dependencyCount; }
    public Integer getMappedCount() { return mappedCount; }
    public void setMappedCount(Integer mappedCount) { this.mappedCount = mappedCount; }
    public Integer getBoundCount() { return boundCount; }
    public void setBoundCount(Integer boundCount) { this.boundCount = boundCount; }
}
