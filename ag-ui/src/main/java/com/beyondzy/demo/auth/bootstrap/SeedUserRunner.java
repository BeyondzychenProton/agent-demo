package com.beyondzy.demo.auth.bootstrap;

import com.beyondzy.demo.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 启动时确保 auth_session 表与演示用户（优先 id=1）存在。 */
@Component
@Order(1)
public class SeedUserRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedUserRunner.class);

    private final JdbcTemplate jdbc;
    private final UserService users;
    private final String seedName;
    private final String seedPhone;
    private final String seedPassword;

    public SeedUserRunner(
            JdbcTemplate jdbc,
            UserService users,
            @Value("${chat.auth.seed-name:演示用户}") String seedName,
            @Value("${chat.auth.seed-phone}") String seedPhone,
            @Value("${chat.auth.seed-password}") String seedPassword) {
        this.jdbc = jdbc;
        this.users = users;
        this.seedName = seedName;
        this.seedPhone = seedPhone;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS auth_session (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    token_hash CHAR(64) NOT NULL,
                    expires_at BIGINT NOT NULL,
                    renewed_at BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE KEY uk_auth_session_token_hash (token_hash),
                    KEY idx_auth_session_user (user_id),
                    KEY idx_auth_session_expires (expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        var user = users.ensureSeedUser(1L, seedName, seedPhone, seedPassword);
        log.info("种子用户已就绪 id={} phone={}", user.getId(), user.getPhone());
    }
}
