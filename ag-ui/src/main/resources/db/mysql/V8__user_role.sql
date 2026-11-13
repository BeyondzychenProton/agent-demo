-- ============================================================
-- V8（增量迁移，已有环境执行一次）：用户角色
--
-- role: 'admin' | 'user'
-- 管理员可以上传/删除技能；普通用户只能查看（平台技能 + 分给自己的技能）。
-- ============================================================

ALTER TABLE app_user
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'user'
        COMMENT '角色：admin / user' AFTER password_hash;

-- 指定管理员（按实际账号修改）
UPDATE app_user SET role = 'admin' WHERE id = 1;
