CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '用户主键',
    name VARCHAR(100) NOT NULL COMMENT '用户姓名',
    phone VARCHAR(24) NOT NULL COMMENT '用户手机号',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希，不存储明文',
    created_at BIGINT NOT NULL COMMENT '创建时间，毫秒级 Unix 时间戳',
    updated_at BIGINT NOT NULL COMMENT '更新时间，毫秒级 Unix 时间戳',
    UNIQUE KEY uk_app_user_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
