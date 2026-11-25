package com.beyondzy.demo.readmodel;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 变更续读查询投影（非单表行模型）：revision 来自 rm_change，payload 来自 rm_item。
 * 因跨表 JOIN，不加 @TableName。
 */
@Schema(description = "变更续读查询投影（非单表行模型）：revision 来自 rm_change，payload 来自 rm_item。")
public class ReadModelChangeRowBean {
    @Schema(description = "读模型修订号")
    private Long revision;
    @Schema(description = "展示条目 JSON 内容")
    private String payload;
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
