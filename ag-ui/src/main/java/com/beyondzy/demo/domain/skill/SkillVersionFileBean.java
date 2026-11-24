package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;

/** 版本内的原始文件字节。脚本只保留，不表示可以执行。 */
@TableName("ai_skill_version_file")
public class SkillVersionFileBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("version_id")
    private Long versionId;
    @TableField("resource_path")
    private String resourcePath;
    @TableField("file_kind")
    private String fileKind;
    private byte[] content;
    @TableField("content_sha256")
    private String contentSha256;
    @TableField("byte_size")
    private Integer byteSize;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getResourcePath() { return resourcePath; }
    public void setResourcePath(String resourcePath) { this.resourcePath = resourcePath; }
    public String getFileKind() { return fileKind; }
    public void setFileKind(String fileKind) { this.fileKind = fileKind; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public Integer getByteSize() { return byteSize; }
    public void setByteSize(Integer byteSize) { this.byteSize = byteSize; }
}
