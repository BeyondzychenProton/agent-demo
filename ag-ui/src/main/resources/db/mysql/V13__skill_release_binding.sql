-- Skills 原型对齐。前置 V11、V12；手工执行，不在应用启动建表。
-- 已有数据库先备份。版本内容迁移由管理员调用 POST /api/admin/skills/migrate-releases 执行，可重跑。
-- 不根据历史版本顺序推断生效版本；不删除/改写已有内容和引用。
CREATE TABLE IF NOT EXISTS ai_skill_release (
    skill_id BIGINT NOT NULL COMMENT 'agentscope_skills.id',
    current_version_id BIGINT UNSIGNED NOT NULL COMMENT '明确的当前生效 ai_skill_version.id',
    PRIMARY KEY (skill_id),
    UNIQUE KEY uk_ai_skill_release_version (current_version_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='技能当前生效版本指针';

-- 以下 ALTER 各执行一次；重跑前通过 information_schema.columns 检查，已存在则跳过。
ALTER TABLE ai_agent_skill ADD COLUMN version_id BIGINT UNSIGNED NULL COMMENT '固定的已发布技能版本；存量迁移后填充';
ALTER TABLE ai_skill_version ADD COLUMN display_name VARCHAR(128) NULL COMMENT '版本展示名称，与唯一技能标识分离';
