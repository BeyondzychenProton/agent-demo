package com.beyondzy.demo.domain.readmodel;

/**
 * 变更续读查询投影（非单表行模型）：revision 来自 rm_change，payload 来自 rm_item。
 * 因跨表 JOIN，不加 @TableName。
 */
public class ReadModelChangeRowBean {
    private Long revision;
    private String payload;
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
