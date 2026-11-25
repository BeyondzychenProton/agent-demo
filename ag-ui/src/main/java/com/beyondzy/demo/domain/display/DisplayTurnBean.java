package com.beyondzy.demo.domain.display;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/** 已完成会话展示快照中的运行轮次记录。对应表：display_turn。 */
@Schema(description = "已完成会话展示快照中的运行轮次记录。对应表：display_turn。")
@TableName("display_turn")
@Data
public class DisplayTurnBean {
    @Schema(description = "AgentScope 运行 ID")
    private String runId;
    @Schema(description = "运行轮次状态")
    private String status;
}
