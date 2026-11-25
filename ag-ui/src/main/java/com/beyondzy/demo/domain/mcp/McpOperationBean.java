package com.beyondzy.demo.domain.mcp;

import com.baomidou.mybatisplus.annotation.*;
import com.beyondzy.demo.domain.BaseAuditBean;
import java.time.LocalDateTime;

@TableName("ai_mcp_operation")
public class McpOperationBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long serviceId;
    private Long toolId;
    private String action;
    private Long actorId;
    private String actorName;
    private String origin;
    private String result;
    private String detailJson;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long value) { serviceId = value; }
    public Long getToolId() { return toolId; }
    public void setToolId(Long value) { toolId = value; }
    public String getAction() { return action; }
    public void setAction(String value) { action = value; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long value) { actorId = value; }
    public String getActorName() { return actorName; }
    public void setActorName(String value) { actorName = value; }
    public String getOrigin() { return origin; }
    public void setOrigin(String value) { origin = value; }
    public String getResult() { return result; }
    public void setResult(String value) { result = value; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String value) { detailJson = value; }
}
