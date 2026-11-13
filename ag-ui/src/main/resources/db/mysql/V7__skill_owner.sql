-- ============================================================
-- V7（增量迁移，已有环境执行一次）：技能归属
--
-- 为什么 owner_user_id 用 0 而不是 NULL：
--   MySQL 的 UNIQUE 允许多个 NULL，若用 NULL 表示"平台公共"，
--   平台层可以插入同名技能，唯一性就失效了。0 没有这个问题。
--
-- 迁移后：
--   owner_user_id = 0   → 平台公共技能（所有人可见）
--   owner_user_id = N   → 仅用户 N 可见
--   可见集合 = 平台 ∪ 本人；同名时本人优先（应用层合并）
-- ============================================================

ALTER TABLE agentscope_skills
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '归属用户；0=平台公共' AFTER source;

ALTER TABLE agentscope_skills
    DROP INDEX `name`,
    ADD UNIQUE KEY uk_skill_owner_name (owner_user_id, name);
