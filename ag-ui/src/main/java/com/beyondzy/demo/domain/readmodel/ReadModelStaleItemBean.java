package com.beyondzy.demo.domain.readmodel;

/** 清理候选的过期条目版本。对应表：rm_item（已关闭且超出 TTL）。 */
public class ReadModelStaleItemBean {
    private String thread;
    private String id;
    private Long revision;
    public String getThread() { return thread; }
    public void setThread(String thread) { this.thread = thread; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
}
