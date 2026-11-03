package com.beyondzy.demo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 会话目录的 MySQL 持久化对象。 */
@TableName("thread_catalog")
public class ThreadCatalogBean extends BaseAuditBean {
    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    private Long seq;
    private Boolean named;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public Boolean getNamed() { return named; }
    public void setNamed(Boolean named) { this.named = named; }
}
