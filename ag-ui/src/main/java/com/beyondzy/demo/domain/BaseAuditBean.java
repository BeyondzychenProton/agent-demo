package com.beyondzy.demo.domain;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 业务表共用的审计时间字段。 */
@Schema(description = "业务表共用的审计时间字段。")
@Data
public abstract class BaseAuditBean {
    @Schema(description = "创建时间")
    @TableField(value = "gmt_create", fill = FieldFill.INSERT)
    private LocalDateTime gmtCreate;
    @Schema(description = "更新时间")
    @TableField(value = "gmt_modified", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime gmtModified;
}
