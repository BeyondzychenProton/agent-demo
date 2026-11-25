package com.beyondzy.demo.domain.session;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/** AgentScope 会话日志对象在 MySQL 中的持久化模型。对应表：agent_session_object。 */
@Schema(description = "AgentScope 会话日志对象在 MySQL 中的持久化模型。对应表：agent_session_object。")
@TableName("agent_session_object")
@Data
public class SessionObjectBean {
    @Schema(description = "所属用户主键")
    private Long userId;
    @Schema(description = "AgentScope 日志对象路径")
    private String objectPath;
    @Schema(description = "对象版本，用于原子比较并交换")
    private Long version;
    @Schema(description = "SessionLog 持久化内容")
    private byte[] payload;
}
