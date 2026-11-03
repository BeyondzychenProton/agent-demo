package com.beyondzy.demo.domain;

/** 完整基础展示视图缓存记录。 */
public class DisplayViewBean {
    private Integer format;
    private Boolean hasInteractions;
    private String payload;
    public Integer getFormat() { return format; }
    public void setFormat(Integer format) { this.format = format; }
    public Boolean getHasInteractions() { return hasInteractions; }
    public void setHasInteractions(Boolean hasInteractions) { this.hasInteractions = hasInteractions; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
