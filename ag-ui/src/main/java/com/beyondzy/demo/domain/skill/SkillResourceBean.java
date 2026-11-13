package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 技能附属文件。对应表：agentscope_skill_resources。主键是 (id, resource_path)，删除随技能级联。
 *
 * <p>{@code id} 是所属技能 id（不是自增列），所以显式声明 {@code IdType.INPUT}：
 * 否则 MyBatis-Plus 会把名为 id 的字段当成自增/雪花主键去生成值。
 */
@TableName("agentscope_skill_resources")
public class SkillResourceBean {
    @TableId(type = IdType.INPUT)
    private Long id;
    @TableField("resource_path")
    private String resourcePath;
    @TableField("resource_content")
    private String resourceContent;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getResourcePath() { return resourcePath; }
    public void setResourcePath(String resourcePath) { this.resourcePath = resourcePath; }
    public String getResourceContent() { return resourceContent; }
    public void setResourceContent(String resourceContent) { this.resourceContent = resourceContent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
