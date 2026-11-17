CREATE TABLE IF NOT EXISTS app_directory_department (
    source_id BIGINT UNSIGNED NOT NULL COMMENT '来源部门 ID',
    source_version BIGINT UNSIGNED NOT NULL COMMENT '已处理的最新来源版本',
    parent_id BIGINT UNSIGNED NULL COMMENT '父部门的来源 ID',
    name VARCHAR(30) NULL COMMENT '部门名称',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '同级显示顺序',
    status CHAR(1) NULL COMMENT '来源状态：0 启用 / 1 停用',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '软删除标记：0 未删 / 1 已删',
    gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (source_id),
    KEY idx_app_directory_department_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台组织部门表';

CREATE TABLE IF NOT EXISTS app_directory_user (
    source_id BIGINT UNSIGNED NOT NULL COMMENT '来源用户 ID',
    source_version BIGINT UNSIGNED NOT NULL COMMENT '已处理的最新来源版本',
    department_id BIGINT UNSIGNED NULL COMMENT '所属部门的来源 ID',
    account VARCHAR(50) NULL COMMENT '来源账号',
    name VARCHAR(50) NULL COMMENT '成员显示名称',
    email VARCHAR(50) NULL COMMENT '成员邮箱',
    status CHAR(1) NULL COMMENT '来源账号状态：0 启用 / 1 停用',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '软删除标记：0 未删 / 1 已删',
    gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (source_id),
    KEY idx_app_directory_user_department (department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台组织用户表';

CREATE TABLE IF NOT EXISTS app_directory_push_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '推送历史主键',
    source VARCHAR(32) NOT NULL COMMENT '推送来源',
    status VARCHAR(16) NOT NULL COMMENT '处理结果：ACCEPTED 已提交 / REJECTED 参数错误 / FAILED 写入失败',
    department_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '请求中的部门记录数',
    user_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '请求中的用户记录数',
    payload JSON NULL COMMENT '校验通过的请求字段，不含令牌或额外字段',
    gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '历史入库时间',
    PRIMARY KEY (id),
    KEY idx_app_directory_push_history_source_time (source, gmt_create)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组织通讯录推送历史表';
