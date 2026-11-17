package com.beyondzy.demo.domain.directory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;

/** 用户通讯录实体，主键沿用满天星用户 ID。 */
@TableName("app_directory_user")
public class DirectoryUserBean extends BaseAuditBean {
    @TableId(type = IdType.INPUT)
    private Long sourceId;
    private Long sourceVersion;
    private Long departmentId;
    private String account;
    private String name;
    private String email;
    private String status;
    private Integer isDeleted;

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(Long sourceVersion) { this.sourceVersion = sourceVersion; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
}
