package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 技能附属文件。对应表：agentscope_skill_resources。主键是 (id, resource_path)，删除随技能级联。
 *
 * <p>只给路径查询用。写入走 JDBC 技能库，不经本实体插入。
 */
@TableName("agentscope_skill_resources")
public class SkillResourceBean {
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
