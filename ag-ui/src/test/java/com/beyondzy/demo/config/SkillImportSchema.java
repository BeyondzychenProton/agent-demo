package com.beyondzy.demo.config;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 测试用的四张导入表。类型按 H2 的 MySQL 模式收窄，字段和约束与 V12 对齐。 */
public final class SkillImportSchema {
    private SkillImportSchema() {}

    public static void create(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS ai_skill_release (skill_id BIGINT PRIMARY KEY, current_version_id BIGINT NOT NULL UNIQUE)");
        jdbc.execute("ALTER TABLE ai_agent_skill ADD COLUMN IF NOT EXISTS version_id BIGINT NULL");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_skill_import (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    request_key VARCHAR(64) NOT NULL,
                    created_by BIGINT NOT NULL,
                    state VARCHAR(24) NOT NULL,
                    filename VARCHAR(255) NOT NULL,
                    package_sha256 CHAR(64) NOT NULL,
                    package_body BLOB NULL,
                    target_name VARCHAR(64) NULL,
                    entry_policy VARCHAR(32) NULL,
                    content_policy VARCHAR(32) NULL,
                    check_report_json CLOB NULL,
                    checker_version VARCHAR(64) NULL,
                    confirmed_by BIGINT NULL,
                    confirmed_at TIMESTAMP NULL,
                    result_version_id BIGINT NULL,
                    result_json CLOB NULL,
                    error_code VARCHAR(64) NULL,
                    expires_at TIMESTAMP NULL,
                    revision BIGINT NOT NULL DEFAULT 0,
                    gmt_create TIMESTAMP NOT NULL,
                    gmt_modified TIMESTAMP NOT NULL,
                    UNIQUE (created_by, request_key)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_skill_version (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    skill_name VARCHAR(64) NOT NULL,
                    skill_id BIGINT NULL,
                    version_no INT NOT NULL,
                    package_version VARCHAR(64) NULL,
                    platform_version VARCHAR(64) NULL,
                    state VARCHAR(24) NOT NULL,
                    description CLOB NOT NULL,
                    display_name VARCHAR(128) NULL,
                    skill_content CLOB NOT NULL,
                    metadata_json CLOB NULL,
                    source_rules_json CLOB NULL,
                    visible_scope CHAR(1) NOT NULL DEFAULT '0',
                    owner_dept_id BIGINT NULL,
                    created_by BIGINT NOT NULL,
                    import_id BIGINT NULL,
                    published_by BIGINT NULL,
                    published_at TIMESTAMP NULL,
                    revision BIGINT NOT NULL DEFAULT 0,
                    gmt_create TIMESTAMP NOT NULL,
                    gmt_modified TIMESTAMP NOT NULL,
                    UNIQUE (skill_name, version_no),
                    UNIQUE (skill_name, platform_version),
                    UNIQUE (import_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_skill_version_file (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    version_id BIGINT NOT NULL,
                    resource_path VARCHAR(500) NOT NULL,
                    file_kind VARCHAR(24) NOT NULL,
                    content BLOB NOT NULL,
                    content_sha256 CHAR(64) NOT NULL,
                    byte_size INT NOT NULL,
                    gmt_create TIMESTAMP NOT NULL,
                    gmt_modified TIMESTAMP NOT NULL,
                    UNIQUE (version_id, resource_path)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_skill_version_dependency (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    version_id BIGINT NOT NULL,
                    source_key VARCHAR(128) NOT NULL,
                    capability_key VARCHAR(128) NULL,
                    required_flag CHAR(1) NOT NULL DEFAULT '1',
                    target_kind VARCHAR(24) NOT NULL,
                    target_provider_key VARCHAR(128) NULL,
                    target_tool_key VARCHAR(128) NULL,
                    mapping_state VARCHAR(24) NOT NULL,
                    reason VARCHAR(1000) NULL,
                    target_snapshot_json CLOB NULL,
                    gmt_create TIMESTAMP NOT NULL,
                    gmt_modified TIMESTAMP NOT NULL,
                    UNIQUE (version_id, source_key)
                )
                """);
    }
}
