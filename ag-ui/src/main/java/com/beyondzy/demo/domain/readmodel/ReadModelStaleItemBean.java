package com.beyondzy.demo.domain.readmodel;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/** 清理候选的过期条目版本。对应表：rm_item（已关闭且超出 TTL）。 */
@Schema(description = "清理候选的过期条目版本。对应表：rm_item（已关闭且超出 TTL）。")
@TableName("rm_item")
@Data
public class ReadModelStaleItemBean {
    @Schema(description = "会话 ID")
    private String thread;
    @Schema(description = "投影条目 ID")
    private String id;
    @Schema(description = "该条目版本开始生效的修订号")
    private Long revision;
}
