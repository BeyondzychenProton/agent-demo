package com.beyondzy.demo.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;

/**
 * 数据表审计字段基类。
 *
 * <p>需要自动维护创建时间和更新时间的数据库 Bean 可以继承此类。字段使用毫秒级 Unix 时间戳，
 * 由 {@code AuditMetaObjectHandler} 在新增和更新时统一填充。
 */
public abstract class BaseAuditBean {
    /** 记录创建时间，毫秒级 Unix 时间戳，仅在插入时自动填充。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Long createdAt;

    /** 记录最后更新时间，毫秒级 Unix 时间戳，在插入和更新时自动填充。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Long updatedAt;

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
