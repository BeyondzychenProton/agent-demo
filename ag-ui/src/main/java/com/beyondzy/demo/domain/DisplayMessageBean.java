package com.beyondzy.demo.domain;

/** 已完成会话展示快照中的消息记录。 */
public class DisplayMessageBean {
    private String messageId;
    private String role;
    private String content;
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
