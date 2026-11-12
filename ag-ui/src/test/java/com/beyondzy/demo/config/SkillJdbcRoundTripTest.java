package com.beyondzy.demo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.config.skill.SkillSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** H2 上走官方 JDBC 技能库：空库建表，保存后能读回，删除时资源级联消失。 */
class SkillJdbcRoundTripTest {

    @Test
    void emptyStartupCreatesOnlySkillTables() throws Exception {
        DataSource dataSource = memory();
        AbstractJdbcDialect dialect = SkillJdbc.dialect(dataSource);
        assertFalse(dialect.isBaseTablesEnabled());
        assertTrue(dialect.isSkillTablesEnabled());
        try (JdbcAgentSkillRepository ignored = new JdbcAgentSkillRepository(dataSource, dialect)) {
            List<String> tables = tableNames(dataSource);
            assertTrue(tables.contains("agentscope_skills"), tables.toString());
            assertTrue(tables.contains("agentscope_skill_resources"), tables.toString());
            assertEquals(2, tables.size(), tables.toString());
        }
    }

    @Test
    void startupSqlCreatesTheSameTwoTables() throws Exception {
        DataSource dataSource = memory();
        SkillSchema.ensure(new JdbcTemplate(dataSource));
        List<String> tables = tableNames(dataSource);
        assertTrue(tables.contains("agentscope_skills"), tables.toString());
        assertTrue(tables.contains("agentscope_skill_resources"), tables.toString());
    }

    @Test
    void saveGetAndDeleteCascadeResources() {
        DataSource dataSource = memory();
        AbstractJdbcDialect dialect = SkillJdbc.dialect(dataSource);
        try (JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, dialect)) {
            AgentSkill skill = AgentSkill.builder()
                    .name("meeting-booking")
                    .description("预约会议")
                    .skillContent("---\nname: meeting-booking\n---\n# 会议\n")
                    .source(repo.getSource())
                    .addResource("references/guide.md", "指南")
                    .addResource("scripts/echo.sh", "echo hi")
                    .build();

            assertTrue(repo.save(List.of(skill), false));
            AgentSkill loaded = repo.getSkill("meeting-booking");
            assertEquals("指南", loaded.getResource("references/guide.md"));
            assertEquals("echo hi", loaded.getResource("scripts/echo.sh"));
            assertEquals(repo.getSource(), loaded.getSource());
            assertEquals(2, countResources(dataSource));

            assertThrows(IllegalStateException.class, () -> repo.save(List.of(skill), false));

            AgentSkill replaced = skill.toBuilder().skillContent("replaced").build();
            assertTrue(repo.save(List.of(replaced), true));
            assertEquals("replaced", repo.getSkill("meeting-booking").getSkillContent());

            assertTrue(repo.delete("meeting-booking"));
            assertThrows(IllegalArgumentException.class, () -> repo.getSkill("meeting-booking"));
            assertEquals(0, countResources(dataSource));
        }
    }

    private static DataSource memory() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:skills-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }

    private static List<String> tableNames(DataSource dataSource) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet rows = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
            while (rows.next()) {
                String schema = rows.getString("TABLE_SCHEM");
                if (schema != null && !schema.equalsIgnoreCase("PUBLIC")) continue;
                String name = rows.getString("TABLE_NAME");
                if (name != null) names.add(name.toLowerCase());
            }
        }
        return names;
    }

    private static int countResources(DataSource dataSource) {
        Integer count = new JdbcTemplate(dataSource)
                .queryForObject("SELECT COUNT(*) FROM agentscope_skill_resources", Integer.class);
        return count == null ? 0 : count;
    }
}
