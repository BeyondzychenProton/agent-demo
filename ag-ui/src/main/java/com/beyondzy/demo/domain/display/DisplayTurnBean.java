package com.beyondzy.demo.domain.display;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/** 已完成会话展示快照中的运行轮次记录。对应表：display_turn。 */
@Schema(description = "已完成会话展示快照中的运行轮次记录。对应表：display_turn。")
@TableName("display_turn")
public class DisplayTurnBean {
    @Schema(description = "AgentScope 运行 ID")
    private String runId;
    @Schema(description = "运行轮次状态")
    private String status;
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
