package com.beyondzy.demo.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;

/** AgentScope 会话日志对象在 MySQL 中的持久化模型。对应表：agent_session_object。 */
@TableName("agent_session_object")
public class SessionObjectBean {
    private Long userId;
    private String objectPath;
    private Long version;
    private byte[] payload;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getObjectPath() { return objectPath; }
    public void setObjectPath(String objectPath) { this.objectPath = objectPath; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] payload) { this.payload = payload; }
}
