package com.beyondzy.demo.domain.skill;

import lombok.Data;
import lombok.EqualsAndHashCode;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 智能体和技能的绑定。对应表：ai_agent_skill。
 *
 * <p>覆盖写是先删后插，所以不手写 gmtCreate。时间由审计填充在插入时写入。不做软删。
 */
@Schema(description = "智能体和技能的绑定。对应表：ai_agent_skill。")
@TableName("ai_agent_skill")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentSkillBean extends BaseAuditBean {
    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "智能体标识，当前只有 session-chat")
    private String agentId;
    @Schema(description = "agentscope_skills.id")
    private Long skillId;
    @Schema(description = "固定的已发布技能版本；存量迁移后填充")
    private Long versionId;
    @Schema(description = "保存这次绑定的 app_user.id")
    private Long createdBy;
}
