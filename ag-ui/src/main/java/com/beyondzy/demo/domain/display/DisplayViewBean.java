package com.beyondzy.demo.domain.display;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/** 完整基础展示视图缓存记录。对应表：display_view。 */
@Schema(description = "完整基础展示视图缓存记录。对应表：display_view。")
@TableName("display_view")
@Data
public class DisplayViewBean {
    @Schema(description = "视图序列化格式版本")
    private Integer format;
    @Schema(description = "视图是否包含交互或审批信息")
    private Boolean hasInteractions;
    @Schema(description = "完整展示视图的 JSON 内容")
    private String payload;
}
