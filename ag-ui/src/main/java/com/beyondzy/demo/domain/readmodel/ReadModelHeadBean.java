package com.beyondzy.demo.domain.readmodel;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 读模型水位与恢复检查点。对应表：rm_head。 */
@Schema(description = "读模型水位与恢复检查点。对应表：rm_head。")
@TableName("rm_head")
public class ReadModelHeadBean {
    @Schema(description = "投影代次标识，用于区分重建前后的读模型")
    private String generation;
    @Schema(description = "当前读模型修订号")
    private Long revision;
    @Schema(description = "已投影的执行日志序号")
    private Long execSeq;
    @Schema(description = "已投影的审批收件箱日志序号")
    private Long inboxSeq;
    @Schema(description = "更新时间")
    private LocalDateTime gmtModified;
    @Schema(description = "当前投影租约标识")
    private String leaseTag;
    @Schema(description = "投影恢复检查点 JSON")
    private String checkpoint;
    @Schema(description = "投影控制状态 JSON")
    private String control;
    public String getGeneration() { return generation; }
    public void setGeneration(String generation) { this.generation = generation; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public Long getExecSeq() { return execSeq; }
    public void setExecSeq(Long execSeq) { this.execSeq = execSeq; }
    public Long getInboxSeq() { return inboxSeq; }
    public void setInboxSeq(Long inboxSeq) { this.inboxSeq = inboxSeq; }
    public LocalDateTime getGmtModified() { return gmtModified; }
    public void setGmtModified(LocalDateTime gmtModified) { this.gmtModified = gmtModified; }
    public String getLeaseTag() { return leaseTag; }
    public void setLeaseTag(String leaseTag) { this.leaseTag = leaseTag; }
    public String getCheckpoint() { return checkpoint; }
    public void setCheckpoint(String checkpoint) { this.checkpoint = checkpoint; }
    public String getControl() { return control; }
    public void setControl(String control) { this.control = control; }
}
