package com.beyondzy.demo.domain.readmodel;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/** 当前条目载荷查询结果。对应表：rm_item（until_revision IS NULL）。 */
@Schema(description = "当前条目载荷查询结果。对应表：rm_item（until_revision IS NULL）。")
@TableName("rm_item")
@Data
public class ReadModelItemPayloadBean {
    @Schema(description = "展示条目 JSON 内容")
    private String payload;
}
