package com.beyondzy.demo.domain.mcp;

import com.baomidou.mybatisplus.annotation.*;
import com.beyondzy.demo.domain.BaseAuditBean;
import java.time.LocalDateTime;

@TableName("ai_mcp_tool")
public class McpToolBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long serviceId;
    private String toolKey;
    private String description;
    private String inputSchemaJson;
    private Integer writeFlag;
    private String definitionHash;
    private Integer enabled;
    private Integer present;
    private Long controlVersion;
    private LocalDateTime firstDiscoveredAt;
    private LocalDateTime lastSeenAt;
    private Long updatedBy;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long value) { serviceId = value; }
    public String getToolKey() { return toolKey; }
    public void setToolKey(String value) { toolKey = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getInputSchemaJson() { return inputSchemaJson; }
    public void setInputSchemaJson(String value) { inputSchemaJson = value; }
    public Integer getWriteFlag() { return writeFlag; }
    public void setWriteFlag(Integer value) { writeFlag = value; }
    public String getDefinitionHash() { return definitionHash; }
    public void setDefinitionHash(String value) { definitionHash = value; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer value) { enabled = value; }
    public Integer getPresent() { return present; }
    public void setPresent(Integer value) { present = value; }
    public Long getControlVersion() { return controlVersion; }
    public void setControlVersion(Long value) { controlVersion = value; }
    public LocalDateTime getFirstDiscoveredAt() { return firstDiscoveredAt; }
    public void setFirstDiscoveredAt(LocalDateTime value) { firstDiscoveredAt = value; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime value) { lastSeenAt = value; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long value) { updatedBy = value; }
}
