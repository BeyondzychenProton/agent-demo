package com.beyondzy.demo.service.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import java.util.List;

/**
 * 按用户可见的技能仓库：平台技能 + 本人技能，同名时本人优先。
 *
 * <p>框架会在每次调用时按 {@link RuntimeContext} 取技能（{@code HarnessSkillMiddleware}
 * 识别 {@link RuntimeContextSkillRepository}），所以<b>一个 Agent 实例就能做到千人千面</b>，
 * 不需要为每个用户建实例。
 *
 * <p>写入不走这里：技能上传/删除统一走管理接口（管理员），避免模型侧或并发路径写库。
 */
public class UserScopedSkillRepository implements RuntimeContextSkillRepository {

    /** 仓库标识，会进 skillId：{@code name + "_" + source}。 */
    public static final String SOURCE = "jdbc";

    private final SkillStore store;
    private volatile boolean writeable;

    public UserScopedSkillRepository(SkillStore store) {
        this.store = store;
    }

    @Override
    public List<AgentSkill> getAllSkills(RuntimeContext context) {
        return store.listVisible(ownerOf(context)).stream()
                .map(store::toAgentSkill)
                .toList();
    }

    @Override
    public AgentSkill getSkill(String name, RuntimeContext context) {
        SkillStore.SkillRecord record = store.findVisible(ownerOf(context), name);
        if (record == null) {
            throw new IllegalArgumentException("技能不存在: " + name);
        }
        return store.toAgentSkill(record);
    }

    // ---- 无上下文方法：一律按"平台公共"处理，避免无身份调用拿到别人的私有技能 ----

    @Override
    public AgentSkill getSkill(String name) {
        SkillStore.SkillRecord record = store.findVisible(SkillStore.PLATFORM_OWNER, name);
        if (record == null) {
            throw new IllegalArgumentException("技能不存在: " + name);
        }
        return store.toAgentSkill(record);
    }

    @Override
    public List<String> getAllSkillNames() {
        return store.listVisible(SkillStore.PLATFORM_OWNER).stream()
                .map(SkillStore.SkillRecord::name)
                .toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return store.listVisible(SkillStore.PLATFORM_OWNER).stream()
                .map(store::toAgentSkill)
                .toList();
    }

    @Override
    public boolean skillExists(String name) {
        return store.findVisible(SkillStore.PLATFORM_OWNER, name) != null;
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        throw new UnsupportedOperationException("技能写入请走管理接口 /api/skills");
    }

    @Override
    public boolean delete(String name) {
        throw new UnsupportedOperationException("技能删除请走管理接口 /api/skills");
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(SOURCE, "agentscope_skills", writeable);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    /** 框架 userId 是字符串（本项目是数字）；解析失败按平台视图处理。 */
    private static long ownerOf(RuntimeContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            return SkillStore.PLATFORM_OWNER;
        }
        try {
            return Long.parseLong(context.getUserId().strip());
        } catch (NumberFormatException error) {
            return SkillStore.PLATFORM_OWNER;
        }
    }
}
