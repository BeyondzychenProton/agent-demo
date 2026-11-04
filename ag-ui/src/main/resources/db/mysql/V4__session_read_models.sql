-- 展示读模型建表脚本。
-- 这些表是运行时展示索引，内容可由 SessionLog 重建，不设置外键。
CREATE TABLE IF NOT EXISTS display_head (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    thread_id VARCHAR(128) NOT NULL COMMENT '会话 ID',
    seq BIGINT UNSIGNED NOT NULL COMMENT '该展示快照对应的会话日志序号',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_thread_id (thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话展示快照的当前日志序号';

CREATE TABLE IF NOT EXISTS display_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    thread_id VARCHAR(128) NOT NULL COMMENT '会话 ID',
    ordinal INT UNSIGNED NOT NULL COMMENT '消息在快照中的顺序号',
    message_id VARCHAR(255) NOT NULL COMMENT '消息 ID',
    role VARCHAR(32) NOT NULL COMMENT '消息角色，例如 user 或 assistant',
    content LONGTEXT NOT NULL COMMENT '消息文本内容',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_thread_ordinal (thread_id, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话展示快照中的消息列表';

CREATE TABLE IF NOT EXISTS display_turn (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    thread_id VARCHAR(128) NOT NULL COMMENT '会话 ID',
    ordinal INT UNSIGNED NOT NULL COMMENT '运行轮次在快照中的顺序号',
    run_id VARCHAR(255) NOT NULL COMMENT 'AgentScope 运行 ID',
    status VARCHAR(64) NOT NULL COMMENT '运行轮次状态',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_thread_ordinal (thread_id, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话展示快照中的运行轮次列表';

CREATE TABLE IF NOT EXISTS display_view (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    thread_id VARCHAR(128) NOT NULL COMMENT '会话 ID',
    seq BIGINT UNSIGNED NOT NULL COMMENT '该视图对应的会话日志序号',
    format INT UNSIGNED NOT NULL COMMENT '视图序列化格式版本',
    is_interactive TINYINT UNSIGNED NOT NULL COMMENT '视图是否包含交互或审批信息',
    payload LONGTEXT NOT NULL COMMENT '完整展示视图的 JSON 内容',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_thread_id (thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='已完成会话的完整展示视图缓存';

CREATE TABLE IF NOT EXISTS rm_head (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    thread VARCHAR(128) NOT NULL COMMENT '会话 ID',
    generation VARCHAR(64) NOT NULL COMMENT '投影代次标识，用于区分重建前后的读模型',
    revision BIGINT UNSIGNED NOT NULL COMMENT '当前读模型修订号',
    exec_seq BIGINT UNSIGNED NOT NULL COMMENT '已投影的执行日志序号',
    inbox_seq BIGINT UNSIGNED NOT NULL COMMENT '已投影的审批收件箱日志序号',
    lease_tag VARCHAR(512) NOT NULL COMMENT '当前投影租约标识',
    checkpoint LONGTEXT NOT NULL COMMENT '投影恢复检查点 JSON',
    control LONGTEXT NOT NULL COMMENT '投影控制状态 JSON',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_thread (thread)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='展示读模型状态头，记录版本水位和恢复检查点';

CREATE TABLE IF NOT EXISTS rm_raw (
    thread VARCHAR(128) NOT NULL COMMENT '会话 ID',
    id VARCHAR(255) NOT NULL COMMENT '投影条目 ID',
    payload LONGTEXT NOT NULL COMMENT '原始条目 JSON 内容',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (thread, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按会话和条目 ID 保存的原始投影数据';

CREATE TABLE IF NOT EXISTS rm_item (
    thread VARCHAR(128) NOT NULL COMMENT '会话 ID',
    id VARCHAR(255) NOT NULL COMMENT '投影条目 ID',
    revision BIGINT UNSIGNED NOT NULL COMMENT '该条目版本开始生效的修订号',
    until_revision BIGINT UNSIGNED NULL COMMENT '该条目版本失效的修订号，空值表示当前版本',
    order_key BIGINT UNSIGNED NOT NULL COMMENT '条目排序键',
    payload LONGTEXT NOT NULL COMMENT '展示条目 JSON 内容',
    gmt_create DATETIME(3) NOT NULL COMMENT '条目版本创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (thread, id, revision),
    KEY idx_item_page (thread, order_key, revision),
    KEY idx_current_page (thread, until_revision, order_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='带修订历史的会话展示条目投影';

CREATE TABLE IF NOT EXISTS rm_pending (
    thread VARCHAR(128) NOT NULL COMMENT '会话 ID',
    id VARCHAR(255) NOT NULL COMMENT '待处理交互条目 ID',
    order_key BIGINT UNSIGNED NOT NULL COMMENT '交互条目排序键',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (thread, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='当前待处理或处理中审批条目索引';

CREATE TABLE IF NOT EXISTS rm_change (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    thread VARCHAR(128) NOT NULL COMMENT '会话 ID',
    revision BIGINT UNSIGNED NOT NULL COMMENT '读模型修订号',
    item_id VARCHAR(255) NOT NULL COMMENT '发生变更的条目 ID',
    gmt_create DATETIME(3) NOT NULL COMMENT '变更记录创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_thread_revision (thread, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话读模型修订变更记录，用于增量续读';

CREATE TABLE IF NOT EXISTS rm_block (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    hash CHAR(64) NOT NULL COMMENT '内容块的 SHA-256 哈希值',
    body TEXT NOT NULL COMMENT '内容块文本',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hash (hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按内容哈希去重存储的长文本块';

CREATE TABLE IF NOT EXISTS rm_content (
    thread VARCHAR(128) NOT NULL COMMENT '会话 ID',
    id VARCHAR(255) NOT NULL COMMENT '展示条目 ID',
    revision BIGINT UNSIGNED NOT NULL COMMENT '展示条目版本修订号',
    field VARCHAR(32) NOT NULL COMMENT '被分块存储的内容字段名',
    block_no INT UNSIGNED NOT NULL COMMENT '内容块在字段中的顺序号',
    hash CHAR(64) NOT NULL COMMENT '关联内容块的 SHA-256 哈希值',
    encoding VARCHAR(16) NOT NULL COMMENT '内容块编码方式',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (thread, id, revision, field, block_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='展示条目长文本字段到内容块的映射';
