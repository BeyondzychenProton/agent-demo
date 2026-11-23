package com.beyondzy.demo.domain.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.beyondzy.demo.domain.BaseAuditBean;

/**
 * 智能体和技能的绑定。对应表：ai_agent_skill。
 *
 * <p>覆盖写是先删后插，所以不手写 gmtCreate。时间由审计填充在插入时写入。不做软删。
 */
@TableName("ai_agent_skill")
public class AgentSkillBean extends BaseAuditBean {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentId;
    private Long skillId;
    private Long createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
