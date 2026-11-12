package com.beyondzy.demo.config;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public final class SkillTestSchema {
    private SkillTestSchema() {}

    public static void create(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("db/mysql/V6__agent_skill.sql"))
                .execute(dataSource);
    }
}
