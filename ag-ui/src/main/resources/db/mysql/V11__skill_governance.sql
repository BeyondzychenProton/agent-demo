-- 技能治理。手工执行，应用启动不负责建技能表。
-- 框架列保持 V6 的名字。source 不在本脚本里修改，也不建 namespace。
--
-- ai_agent_skill 是归属/关系表：
-- 具备 id、gmt_create、gmt_modified。created_by 记录谁保存这次绑定。
-- 覆盖写是先删后插，不再设 updated_by。不做 is_deleted，删除就是物理删除。
-- uk_ai_agent_skill(agent_id, skill_id)：同一智能体不能重复绑定同一技能。
-- idx_ai_agent_skill_skill(skill_id)：删除技能前按技能查还有没有绑定。
-- 不建外键，关联由管理服务检查。

ALTER TABLE agentscope_skills
    ADD COLUMN status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0 启用 / 1 停用';

ALTER TABLE agentscope_skills
    ADD COLUMN visible_scope CHAR(1) NOT NULL DEFAULT '0' COMMENT '0 公开 / 1 本部门 / 2 仅本人';

ALTER TABLE agentscope_skills
    ADD COLUMN owner_dept_id BIGINT UNSIGNED NULL COMMENT '本部门可见时比较的部门来源 ID';

ALTER TABLE agentscope_skills
    ADD COLUMN create_by BIGINT UNSIGNED NULL COMMENT '创建人 app_user.id，仅本人可见时比较';

CREATE TABLE IF NOT EXISTS ai_agent_skill (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    agent_id VARCHAR(128) NOT NULL COMMENT '智能体标识，当前只有 session-chat',
    skill_id BIGINT NOT NULL COMMENT 'agentscope_skills.id',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '保存这次绑定的 app_user.id',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_agent_skill (agent_id, skill_id),
    KEY idx_ai_agent_skill_skill (skill_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='智能体与技能的绑定';

-- 升级前已经在聊天里生效的技能，绑到 session-chat。没有用户时这条插入零行。
-- 此后新增的技能不走这段，所以不要在已有绑定表上反复执行。
INSERT INTO ai_agent_skill (agent_id, skill_id, created_by, gmt_create, gmt_modified)
SELECT 'session-chat', s.id, actor.id, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM agentscope_skills s
CROSS JOIN (
    SELECT id
    FROM app_user
    WHERE is_deleted = 0
    ORDER BY CASE WHEN is_admin = 1 THEN 0 ELSE 1 END, id
    LIMIT 1
) actor
WHERE NOT EXISTS (
    SELECT 1 FROM ai_agent_skill existing
    WHERE existing.agent_id = 'session-chat' AND existing.skill_id = s.id
);
