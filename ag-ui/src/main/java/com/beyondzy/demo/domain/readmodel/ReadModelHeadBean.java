package com.beyondzy.demo.domain.readmodel;

/** 读模型水位与恢复检查点。对应表：rm_head。 */
public class ReadModelHeadBean {
    private String generation;
    private Long revision;
    private Long execSeq;
    private Long inboxSeq;
    private Long updatedAt;
    private String leaseTag;
    private String checkpoint;
    private String control;
    public String getGeneration() { return generation; }
    public void setGeneration(String generation) { this.generation = generation; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public Long getExecSeq() { return execSeq; }
    public void setExecSeq(Long execSeq) { this.execSeq = execSeq; }
    public Long getInboxSeq() { return inboxSeq; }
    public void setInboxSeq(Long inboxSeq) { this.inboxSeq = inboxSeq; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public String getLeaseTag() { return leaseTag; }
    public void setLeaseTag(String leaseTag) { this.leaseTag = leaseTag; }
    public String getCheckpoint() { return checkpoint; }
    public void setCheckpoint(String checkpoint) { this.checkpoint = checkpoint; }
    public String getControl() { return control; }
    public void setControl(String control) { this.control = control; }
}
