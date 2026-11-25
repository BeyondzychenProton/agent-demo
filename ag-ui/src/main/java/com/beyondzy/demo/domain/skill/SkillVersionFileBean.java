package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;

/** 版本内的原始文件字节。脚本只保留，不表示可以执行。 */
@Schema(description = "版本内的原始文件字节。脚本只保留，不表示可以执行。")
@TableName("ai_skill_version_file")
public class SkillVersionFileBean extends BaseAuditBean {
    @Schema(description = "版本文件主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "所属 ai_skill_version.id")
    @TableField("version_id")
    private Long versionId;
    @Schema(description = "规范化包内相对路径，版本内唯一")
    @TableField("resource_path")
    private String resourcePath;
    @Schema(description = "SKILL 入口原文 / REFERENCE 参考资料 / SCRIPT 脚本 / ASSET 其他资源")
    @TableField("file_kind")
    private String fileKind;
    @Schema(description = "文件原始字节，允许空文件，不允许 NULL")
    private byte[] content;
    @Schema(description = "文件字节的 SHA-256，统一小写十六进制")
    @TableField("content_sha256")
    private String contentSha256;
    @Schema(description = "文件字节数，由服务端计算")
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
