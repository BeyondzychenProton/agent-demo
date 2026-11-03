package com.beyondzy.demo.domain.readmodel;

/** 变更续读行：revision 与对应 item payload。对应表：rm_change JOIN rm_item。 */
public class ReadModelChangeRowBean {
    private Long revision;
    private String payload;
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
