package com.beyondzy.demo.domain.display;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/** 已完成会话展示快照中的消息记录。对应表：display_message。 */
@Schema(description = "已完成会话展示快照中的消息记录。对应表：display_message。")
@TableName("display_message")
@Data
public class DisplayMessageBean {
    @Schema(description = "消息 ID")
    private String messageId;
    @Schema(description = "消息角色，例如 user 或 assistant")
    private String role;
    @Schema(description = "消息文本内容")
    private String content;
}
