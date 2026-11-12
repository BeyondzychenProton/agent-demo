package com.beyondzy.demo.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 启动时确保技能表存在。方言的 autoCreate 之外再执行同一份脚本。 */
@Component
@Order(0)
public class SkillSchemaRunner implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public SkillSchemaRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        SkillSchema.ensure(jdbc);
    }
}
