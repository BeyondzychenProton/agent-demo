ALTER TABLE app_user
    ADD COLUMN mantianxing_user_id BIGINT UNSIGNED NULL COMMENT '满天星来源用户 ID',
    ADD UNIQUE KEY uk_app_user_mantianxing_user_id (mantianxing_user_id);
