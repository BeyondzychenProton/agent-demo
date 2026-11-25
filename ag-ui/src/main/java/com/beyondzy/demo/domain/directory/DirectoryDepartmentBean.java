package com.beyondzy.demo.domain.directory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;

/** 部门通讯录实体，主键沿用满天星部门 ID。 */
@Schema(description = "部门通讯录实体，主键沿用满天星部门 ID。")
@TableName("app_directory_department")
public class DirectoryDepartmentBean extends BaseAuditBean {
    public static final String STATUS_ENABLED = "0";
    public static final String STATUS_DISABLED = "1";
    public static final int NOT_DELETED = 0;
    public static final int DELETED = 1;

    @Schema(description = "来源部门 ID")
    @TableId(type = IdType.INPUT)
    private Long sourceId;
    @Schema(description = "已处理的最新来源版本")
    private Long sourceVersion;
    @Schema(description = "父部门的来源 ID")
    private Long parentId;
    @Schema(description = "部门名称")
    private String name;
    @Schema(description = "同级显示顺序")
    private Integer sortOrder;
    @Schema(description = "来源状态：0 启用 / 1 停用")
    private String status;
    @Schema(description = "软删除标记：0 未删 / 1 已删")
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
