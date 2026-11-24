package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import java.time.LocalDateTime;

/** 一次技能包上传。原件和检查报告在确认前就落在这张表，草稿写入失败不能把它清掉。 */
@TableName("ai_skill_import")
public class SkillImportBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("request_key")
    private String requestKey;
    @TableField("created_by")
    private Long createdBy;
    private String state;
    private String filename;
    @TableField("package_sha256")
    private String packageSha256;
    @TableField("package_body")
    private byte[] packageBody;
    @TableField("target_name")
    private String targetName;
    @TableField("entry_policy")
    private String entryPolicy;
    @TableField("content_policy")
    private String contentPolicy;
    @TableField("check_report_json")
    private String checkReportJson;
    @TableField("checker_version")
    private String checkerVersion;
    @TableField("confirmed_by")
    private Long confirmedBy;
    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;
    @TableField("result_version_id")
    private Long resultVersionId;
    @TableField("result_json")
    private String resultJson;
    @TableField("error_code")
    private String errorCode;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    private Long revision;
    /** 列表查询算出来的，不是表字段。1 表示原件还在。 */
    @TableField(exist = false)
    private Integer sourceAvailable;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String requestKey) { this.requestKey = requestKey; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getPackageSha256() { return packageSha256; }
    public void setPackageSha256(String packageSha256) { this.packageSha256 = packageSha256; }
    public byte[] getPackageBody() { return packageBody; }
    public void setPackageBody(byte[] packageBody) { this.packageBody = packageBody; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getEntryPolicy() { return entryPolicy; }
    public void setEntryPolicy(String entryPolicy) { this.entryPolicy = entryPolicy; }
    public String getContentPolicy() { return contentPolicy; }
    public void setContentPolicy(String contentPolicy) { this.contentPolicy = contentPolicy; }
    public String getCheckReportJson() { return checkReportJson; }
    public void setCheckReportJson(String checkReportJson) { this.checkReportJson = checkReportJson; }
    public String getCheckerVersion() { return checkerVersion; }
    public void setCheckerVersion(String checkerVersion) { this.checkerVersion = checkerVersion; }
    public Long getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Long confirmedBy) { this.confirmedBy = confirmedBy; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public Long getResultVersionId() { return resultVersionId; }
    public void setResultVersionId(Long resultVersionId) { this.resultVersionId = resultVersionId; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public Integer getSourceAvailable() { return sourceAvailable; }
    public void setSourceAvailable(Integer sourceAvailable) { this.sourceAvailable = sourceAvailable; }
}
