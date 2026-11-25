package com.beyondzy.demo.domain.session;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 用户会话目录行。对应表：agent_session。 */
@Schema(description = "用户会话目录行。对应表：agent_session。")
public class AgentSessionBean {
    @Schema(description = "主键")
    private Long id;
    @Schema(description = "所属用户主键")
    private Long userId;
    @Schema(description = "Agent 标识")
    private String agentId;
    @Schema(description = "会话标识")
    private String sessionId;
    @Schema(description = "创建时间")
    private LocalDateTime gmtCreate;
    @Schema(description = "更新时间")
    private LocalDateTime gmtModified;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public LocalDateTime getGmtCreate() { return gmtCreate; }
    public void setGmtCreate(LocalDateTime gmtCreate) { this.gmtCreate = gmtCreate; }
    public LocalDateTime getGmtModified() { return gmtModified; }
    public void setGmtModified(LocalDateTime gmtModified) { this.gmtModified = gmtModified; }
}
