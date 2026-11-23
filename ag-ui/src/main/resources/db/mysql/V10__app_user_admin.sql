-- 手工执行。管理员以本列为准，可同时有多位。
-- 设置：UPDATE app_user SET is_admin = 1, gmt_modified = CURRENT_TIMESTAMP(3) WHERE phone = '手机号' AND is_deleted = 0;
-- 取消：UPDATE app_user SET is_admin = 0, gmt_modified = CURRENT_TIMESTAMP(3) WHERE phone = '手机号' AND is_deleted = 0;
ALTER TABLE app_user
    ADD COLUMN is_admin TINYINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '平台管理员：0 否 / 1 是';
