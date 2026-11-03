package com.beyondzy.demo.domain.user;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;

/** 用户等业务表共用的审计字段。 */
public abstract class BaseAuditBean {
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Long createdAt;
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Long updatedAt;

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
