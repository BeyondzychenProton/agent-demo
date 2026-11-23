package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.domain.skill.SkillBean;
import java.util.List;

/**
 * 计算一个人此刻能看见哪些技能。聊天只调用 {@link #namesFor}。
 *
 * <p>本部门按部门 id 相等判断，不沿 parent_id 把下级算进来。
 */
public interface SkillVisibilityService {
    /**
     * 绑定 ∩ 启用 ∩ 当前用户可见的技能名。
     * 用户 id 不是正整数、用户不存在或已删除时返回空列表。
     */
    List<String> namesFor(String agentId, String appUserId);

    /** 保存「本部门」但没填部门时使用。没有可用部门返回 null。 */
    Long departmentIdOf(long appUserId);

    /** 部门存在、未删除且启用。 */
    boolean departmentActive(long departmentId);

    /** 不看启用状态，只看可见范围。认不出的用户返回 false。 */
    boolean visibleTo(SkillBean skill, long appUserId);
}
