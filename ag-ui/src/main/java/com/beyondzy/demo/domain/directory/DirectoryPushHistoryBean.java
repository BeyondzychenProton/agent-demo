package com.beyondzy.demo.domain.directory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 单次推送的处理记录，创建时间由数据库填写。 */
@TableName("app_directory_push_history")
public class DirectoryPushHistoryBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String source;
    private String status;
    private Integer departmentCount;
    private Integer userCount;
    private String payload;
    private LocalDateTime gmtCreate;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getDepartmentCount() { return departmentCount; }
    public void setDepartmentCount(Integer departmentCount) { this.departmentCount = departmentCount; }
    public Integer getUserCount() { return userCount; }
    public void setUserCount(Integer userCount) { this.userCount = userCount; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public LocalDateTime getGmtCreate() { return gmtCreate; }
    public void setGmtCreate(LocalDateTime gmtCreate) { this.gmtCreate = gmtCreate; }
}
