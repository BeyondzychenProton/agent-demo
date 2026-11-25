package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.service.skill.support.SkillDraft;
import com.beyondzy.demo.service.skill.view.SkillAdminView;

import com.beyondzy.demo.contract.PageResult;
import java.util.List;

/**
 * 管理端维护技能正文、参考资料、启停、可见范围，以及和 session-chat 的绑定。
 *
 * <p>写入用自己的 SQL，不调用技能仓库的 save。仓库保持只读。
 */
public interface SkillAdminService {
    PageResult<SkillAdminView.Item> list(String query, String state, int page, int size);

    SkillAdminView.Detail detail(String name);

    SkillAdminView.Detail create(long operatorId, SkillDraft draft);

    /** 路径里的名字就是技能名。请求体里的 name 即使不同也不写回。 */
    SkillAdminView.Detail update(long operatorId, String name, SkillDraft draft);

    void setEnabled(String name, boolean enabled);

    /** 仍被绑定时拒绝，避免删掉聊天还在用的技能。 */
    void delete(String name);

    /**
     * 导入一份 markdown 或 zip。调用方不能指定 source。
     * 可见范围和状态缺省为公开、启用，查询参数可以覆盖。
     */
    SkillAdminView.Detail importDocument(
            long operatorId, String filename, byte[] body, String visibleScope, Long ownerDeptId, String status);

    /** 当前操作人可见、且启用的技能。给绑定页勾选。 */
    List<SkillAdminView.Option> options(long operatorId);

    SkillAdminView.Agent agent(String agentId);

    /** 覆盖写。空列表解除全部绑定。新绑定必须启用，并且当前操作人看得见。 */
    void replaceBindings(long operatorId, String agentId, List<String> names);

    void replaceVersionBindings(long operatorId, String agentId, List<Binding> bindings);

    record Binding(String name, String versionId) {}
}
