package com.beyondzy.demo.domain.display;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/** 已完成会话展示快照中的消息记录。对应表：display_message。 */
@Schema(description = "已完成会话展示快照中的消息记录。对应表：display_message。")
@TableName("display_message")
public class DisplayMessageBean {
    @Schema(description = "消息 ID")
    private String messageId;
    @Schema(description = "消息角色，例如 user 或 assistant")
    private String role;
    @Schema(description = "消息文本内容")
    private String content;
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
