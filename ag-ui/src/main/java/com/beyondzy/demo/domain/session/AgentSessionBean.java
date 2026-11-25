package com.beyondzy.demo.domain.session;

import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 用户会话目录行。对应表：agent_session。 */
@Schema(description = "用户会话目录行。对应表：agent_session。")
@Data
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
}
