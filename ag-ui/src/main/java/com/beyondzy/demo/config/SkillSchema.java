package com.beyondzy.demo.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;

/** 启动时执行技能表脚本。V2–V5 不会自动应用，因此这里单独补这两张表。 */
public final class SkillSchema {
    private static final String SCRIPT = "/db/mysql/V6__agent_skill.sql";

    private SkillSchema() {}

    public static void ensure(JdbcTemplate jdbc) {
        String sql = read();
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) jdbc.execute(trimmed);
        }
    }

    private static String read() {
        try (InputStream in = SkillSchema.class.getResourceAsStream(SCRIPT)) {
            if (in == null) throw new IllegalStateException("缺少技能表脚本 " + SCRIPT);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("读取技能表脚本失败", error);
        }
    }
}
