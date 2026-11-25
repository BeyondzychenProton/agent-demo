-- MCP 管理，手工执行。应用不负责建表；不修改 Skill 或员工数据。
-- CREATE TABLE IF NOT EXISTS 只在表不存在时建表。已有表不会被本脚本修改，也不会补上注释。
CREATE TABLE IF NOT EXISTS ai_mcp_service (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '服务主键',
 provider_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '代码接入的服务键，唯一且区分大小写',
 display_name VARCHAR(128) NOT NULL COMMENT '管理页展示名称，可由保存信息修改',
 description VARCHAR(1000) NULL COMMENT '管理说明，可由保存信息修改',
 owner_name VARCHAR(128) NULL COMMENT '负责人展示名称，可由保存信息修改',
 enabled TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '服务总开关：1 启用，0 停用',
 control_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '人工修改展示信息或服务开关时的乐观锁版本，检测不更新',
 catalog_initialized TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '目录基线是否已建立：1 已建立，0 未建立',
 catalog_revision BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '成功更新目录后递增的版本号',
 check_revision BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '检测序号，仅当完成时仍等于开始序号才写回结果',
 last_check_state VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' COMMENT '最近检测状态：UNKNOWN、CHECKING、HEALTHY、FAILED',
 last_check_started_at DATETIME(3) NULL COMMENT '最近一次检测的开始时间',
 last_checked_at DATETIME(3) NULL COMMENT '最近一次已写回的检测完成时间',
 last_success_at DATETIME(3) NULL COMMENT '最近一次成功发现并更新目录的时间',
 last_latency_ms INT UNSIGNED NULL COMMENT '最近一次已写回检测的耗时毫秒，失败也会记录',
 last_error_code VARCHAR(64) NULL COMMENT '最近一次已写回检测的错误码：UNAUTHENTICATED、SERVER_UNAVAILABLE、TRANSPORT_FAILURE、PROTOCOL、INVALID_CATALOG、DISCOVERY_TIMEOUT、DISCOVERY_INCOMPLETE 或 DISCOVERY_FAILED，成功时为空',
 last_error_message VARCHAR(1000) NULL COMMENT '按错误码写入的固定摘要，成功时为空，不保存远端原文',
 updated_by BIGINT UNSIGNED NULL COMMENT '最近一次人工修改的 app_user.id，检测不更新',
 gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
 gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
 PRIMARY KEY (id),
 UNIQUE KEY uk_ai_mcp_service_provider (provider_key)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='代码接入的 MCP 服务的展示信息、总开关和最近一次连接检测';

CREATE TABLE IF NOT EXISTS ai_mcp_tool (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '工具主键',
 service_id BIGINT UNSIGNED NOT NULL COMMENT '所属 ai_mcp_service.id',
 tool_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '远端原始工具名，同一服务内唯一且区分大小写',
 description TEXT NULL COMMENT '最近一次完整成功发现写入的工具说明',
 input_schema_json JSON NULL COMMENT '最近一次完整成功发现写入的输入结构',
 write_flag TINYINT UNSIGNED NOT NULL COMMENT '本地读写策略：1 写操作，0 读操作',
 definition_hash CHAR(64) NOT NULL COMMENT '工具名、说明、输入结构和读写标记规范化后的 SHA-256 十六进制摘要',
 enabled TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '工具独立开关：1 开放，0 关闭',
 present TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '最近一次完整成功目录中是否仍存在：1 存在，0 已消失',
 control_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '人工修改工具开关时的乐观锁版本，发现刷新不更新',
 first_discovered_at DATETIME(3) NOT NULL COMMENT '首次发现并写入该工具的时间',
 last_seen_at DATETIME(3) NOT NULL COMMENT '最近一次成功目录仍包含该工具的时间',
 updated_by BIGINT UNSIGNED NULL COMMENT '最近一次人工修改工具开关的 app_user.id',
 gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
 gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
 PRIMARY KEY (id),
 UNIQUE KEY uk_ai_mcp_tool_key (service_id, tool_key),
 KEY idx_ai_mcp_tool_state (service_id, present, enabled)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='某个 MCP 服务下远端工具的发现快照和独立开关';

CREATE TABLE IF NOT EXISTS ai_mcp_operation (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '操作记录主键',
 service_id BIGINT UNSIGNED NOT NULL COMMENT '所属 ai_mcp_service.id',
 tool_id BIGINT UNSIGNED NULL COMMENT '工具级操作对应的 ai_mcp_tool.id，服务级操作为空',
 action VARCHAR(40) NOT NULL COMMENT '管理动作：METADATA_UPDATED 保存信息、SERVICE_STATUS_CHANGED 服务开关、TOOL_STATUS_CHANGED 工具开关、CONNECTION_VERIFIED 重新验证、CATALOG_SYNCED 启动同步目录',
 actor_id BIGINT UNSIGNED NULL COMMENT '操作人 app_user.id，系统操作时为空',
 actor_name VARCHAR(128) NULL COMMENT '操作当时写入的展示名',
 origin VARCHAR(16) NOT NULL COMMENT '操作来源：ADMIN 人工，SYSTEM 系统',
 result VARCHAR(16) NOT NULL COMMENT '结果：SUCCESS 成功、FAILED 失败、SUPERSEDED 已被更新的检测取代',
 detail_json JSON NULL COMMENT '本次操作写入的修改前后值、工具键、目录增减计数或错误码',
 gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
 gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
 PRIMARY KEY (id),
 KEY idx_ai_mcp_operation_time (service_id, gmt_create, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='MCP 服务的管理操作记录，只追加，不记录每次工具调用';
