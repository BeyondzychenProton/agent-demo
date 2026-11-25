package com.beyondzy.demo.domain.skill;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 技能附属文件。对应表：agentscope_skill_resources。主键是 (id, resource_path)，删除随技能级联。
 *
 * <p>只给路径查询用。写入走 JDBC 技能库，不经本实体插入。
 */
@Schema(description = "技能附属文件。对应表：agentscope_skill_resources。主键是 (id, resource_path)，删除随技能级联。")
@TableName("agentscope_skill_resources")
@Data
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
}
