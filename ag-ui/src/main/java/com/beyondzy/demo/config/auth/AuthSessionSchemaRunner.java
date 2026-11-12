package com.beyondzy.demo.config.auth;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** 启动时确保登录会话表存在。 */
@Component
@Order(1)
public class AuthSessionSchemaRunner implements ApplicationRunner {
    private final DataSource dataSource;

    public AuthSessionSchemaRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        new ResourceDatabasePopulator(new ClassPathResource("db/mysql/V5__auth_session.sql"))
                .execute(dataSource);
    }
}
