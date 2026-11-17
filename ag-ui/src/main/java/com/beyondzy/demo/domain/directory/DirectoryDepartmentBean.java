package com.beyondzy.demo.domain.directory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;

/** 部门通讯录实体，主键沿用满天星部门 ID。 */
@TableName("app_directory_department")
public class DirectoryDepartmentBean extends BaseAuditBean {
    @TableId(type = IdType.INPUT)
    private Long sourceId;
    private Long sourceVersion;
    private Long parentId;
    private String name;
    private Integer sortOrder;
    private String status;
    private Integer isDeleted;

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(Long sourceVersion) { this.sourceVersion = sourceVersion; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
}
