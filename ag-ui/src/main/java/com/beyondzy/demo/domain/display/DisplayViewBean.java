package com.beyondzy.demo.domain.display;

import com.baomidou.mybatisplus.annotation.TableName;

/** 完整基础展示视图缓存记录。对应表：display_view。 */
@TableName("display_view")
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
