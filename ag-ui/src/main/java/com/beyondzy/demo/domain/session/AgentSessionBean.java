package com.beyondzy.demo.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;

/** 用户会话目录表对应的持久化模型。对应表：agent_session。 */
@TableName("agent_session")
public class AgentSessionBean {
    private Long userId;
    private String agentId;
    private String sessionId;
    private Long createdAt;
    private Long updatedAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
