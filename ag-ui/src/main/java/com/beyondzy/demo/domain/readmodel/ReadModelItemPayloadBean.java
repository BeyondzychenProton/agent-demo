package com.beyondzy.demo.domain.readmodel;

import com.baomidou.mybatisplus.annotation.TableName;

/** 当前条目载荷查询结果。对应表：rm_item（until_revision IS NULL）。 */
@TableName("rm_item")
public class ReadModelItemPayloadBean {
    private String payload;
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
