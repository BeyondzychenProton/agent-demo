CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键',
    name VARCHAR(100) NOT NULL COMMENT '用户姓名',
    phone VARCHAR(24) NOT NULL COMMENT '用户手机号',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希，不存储明文',
    role VARCHAR(32) NOT NULL DEFAULT 'user' COMMENT '角色：admin / user',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '软删除标记：0 未删 / 1 已删',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    phone_active VARCHAR(24) AS (IF(is_deleted = 0, phone, NULL)) STORED COMMENT '未删除时手机号，用于唯一约束',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_phone_active (phone_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
