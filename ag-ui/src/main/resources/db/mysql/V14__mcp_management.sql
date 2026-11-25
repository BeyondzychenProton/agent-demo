-- MCP 管理，手工执行。应用不负责建表；不修改 Skill 或员工数据。
CREATE TABLE IF NOT EXISTS ai_mcp_service (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 provider_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 display_name VARCHAR(128) NOT NULL,
 description VARCHAR(1000) NULL,
 owner_name VARCHAR(128) NULL,
 enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
 control_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 catalog_initialized TINYINT UNSIGNED NOT NULL DEFAULT 0,
 catalog_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 check_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 last_check_state VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
 last_check_started_at DATETIME(3) NULL,
 last_checked_at DATETIME(3) NULL,
 last_success_at DATETIME(3) NULL,
 last_latency_ms INT UNSIGNED NULL,
 last_error_code VARCHAR(64) NULL,
 last_error_message VARCHAR(1000) NULL,
 updated_by BIGINT UNSIGNED NULL,
 gmt_create DATETIME(3) NOT NULL,
 gmt_modified DATETIME(3) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_ai_mcp_service_provider (provider_key)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_mcp_tool (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 service_id BIGINT UNSIGNED NOT NULL,
 tool_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 description TEXT NULL,
 input_schema_json JSON NULL,
 write_flag TINYINT UNSIGNED NOT NULL,
 definition_hash CHAR(64) NOT NULL,
 enabled TINYINT UNSIGNED NOT NULL DEFAULT 0,
 present TINYINT UNSIGNED NOT NULL DEFAULT 1,
 control_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 first_discovered_at DATETIME(3) NOT NULL,
 last_seen_at DATETIME(3) NOT NULL,
 updated_by BIGINT UNSIGNED NULL,
 gmt_create DATETIME(3) NOT NULL,
 gmt_modified DATETIME(3) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_ai_mcp_tool_key (service_id, tool_key),
 KEY idx_ai_mcp_tool_state (service_id, present, enabled)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_mcp_operation (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 service_id BIGINT UNSIGNED NOT NULL,
 tool_id BIGINT UNSIGNED NULL,
 action VARCHAR(40) NOT NULL,
 actor_id BIGINT UNSIGNED NULL,
 actor_name VARCHAR(128) NULL,
 origin VARCHAR(16) NOT NULL,
 result VARCHAR(16) NOT NULL,
 detail_json JSON NULL,
 gmt_create DATETIME(3) NOT NULL,
 gmt_modified DATETIME(3) NOT NULL,
 PRIMARY KEY (id),
 KEY idx_ai_mcp_operation_time (service_id, gmt_create, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
