-- ============================================================
-- V6（基线，仅新环境执行）：技能表
--
-- 注意：本文件已按 V7/V8 之后的最终结构书写（含 owner_user_id）。
-- 已有环境不要重跑本文件，执行 V7__skill_owner.sql / V8__user_role.sql 即可。
--
-- owner_user_id = 0 表示平台公共技能；= 用户 id 表示仅该用户可见。
-- 唯一键是 (owner_user_id, name)，允许同一名字在平台层和用户层各存在一份。
-- 语法保持 MySQL / H2(MySQL 模式) 双兼容：不使用 UNSIGNED / 行内 COMMENT。
-- ============================================================

CREATE TABLE IF NOT EXISTS agentscope_skills (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(255) NOT NULL,
 description TEXT NOT NULL,
 skill_content LONGTEXT NOT NULL,
 source VARCHAR(255) NOT NULL,
 owner_user_id BIGINT NOT NULL DEFAULT 0,
 metadata_json LONGTEXT NULL,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 CONSTRAINT uk_skill_owner_name UNIQUE (owner_user_id, name)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agentscope_skill_resources (
 id BIGINT NOT NULL,
 resource_path VARCHAR(500) NOT NULL,
 resource_content LONGTEXT NOT NULL,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY (id, resource_path),
 FOREIGN KEY (id) REFERENCES agentscope_skills(id) ON DELETE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
