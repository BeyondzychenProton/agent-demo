CREATE TABLE IF NOT EXISTS agent_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户主键',
    agent_id VARCHAR(128) NOT NULL COMMENT 'Agent 标识',
    session_id VARCHAR(128) NOT NULL COMMENT '会话标识',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_agent_session (user_id, agent_id, session_id),
    KEY idx_user_gmt_create (user_id, gmt_create)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户会话目录';

CREATE TABLE IF NOT EXISTS agent_session_object (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户主键',
    object_path VARCHAR(512) NOT NULL COMMENT 'AgentScope 日志对象路径',
    version BIGINT UNSIGNED NOT NULL COMMENT '对象版本，用于原子比较并交换',
    payload LONGBLOB NOT NULL COMMENT 'SessionLog 持久化内容',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_object_path (user_id, object_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AgentScope 会话日志对象';
