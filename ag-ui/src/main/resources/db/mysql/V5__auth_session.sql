CREATE TABLE IF NOT EXISTS auth_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '登录会话主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '关联 app_user.id',
    token_hash CHAR(64) NOT NULL COMMENT '明文 token 的 SHA-256 十六进制',
    expires_at DATETIME(3) NOT NULL COMMENT '绝对过期时间',
    renewed_at DATETIME(3) NOT NULL COMMENT '上次滑动续期时间',
    gmt_create DATETIME(3) NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_session_token_hash (token_hash),
    KEY idx_auth_session_user (user_id),
    KEY idx_auth_session_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='浏览器/多端登录会话';
