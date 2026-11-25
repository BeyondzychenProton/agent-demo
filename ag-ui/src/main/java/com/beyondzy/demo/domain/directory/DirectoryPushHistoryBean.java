package com.beyondzy.demo.domain.directory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 单次推送的处理记录，创建时间由数据库填写。 */
@Schema(description = "单次推送的处理记录，创建时间由数据库填写。")
@TableName("app_directory_push_history")
public class DirectoryPushHistoryBean {
    @Schema(description = "推送历史主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "推送来源")
    private String source;
    @Schema(description = "处理结果：ACCEPTED 已提交 / REJECTED 参数错误 / FAILED 写入失败")
    private String status;
    @Schema(description = "请求中的部门记录数")
    private Integer departmentCount;
    @Schema(description = "请求中的用户记录数")
    private Integer userCount;
    @Schema(description = "校验通过的请求字段，不含令牌或额外字段")
    private String payload;
    @Schema(description = "历史入库时间")
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
