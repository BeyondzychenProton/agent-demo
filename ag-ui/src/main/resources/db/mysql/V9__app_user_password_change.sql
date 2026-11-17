ALTER TABLE app_user
    ADD COLUMN must_change_password TINYINT UNSIGNED NULL
        COMMENT '首次登录改密标记：0 已完成 / 1 必须改密；NULL 为历史记录待初始化';
