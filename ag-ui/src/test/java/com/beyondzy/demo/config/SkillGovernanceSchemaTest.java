package com.beyondzy.demo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.config.skill.SkillGovernanceSchemaRunner;
import com.beyondzy.demo.config.skill.SkillJdbc;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** V6 库补上治理列后可以重复启动；仓库只读，source 保持 jdbc_agentscope_skills。 */
class SkillGovernanceSchemaTest {

    @Test
    void existingV6StartsTwiceAndBindsSkillsOnlyOnFirstUpgrade() {
        DataSource dataSource = memory("upgrade");
        SkillTestSchema.create(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        users(jdbc);
        jdbc.update("INSERT INTO app_user (id, is_deleted, is_admin) VALUES (3, 0, 0), (9, 0, 1)");
        try (JdbcAgentSkillRepository writable = new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource))) {
            assertTrue(writable.save(List.of(skill(writable, "meeting-booking", "已经在用")), false));
            jdbc.update("UPDATE agentscope_skills SET source = 'keep-me' WHERE name = 'meeting-booking'");
        }

        SkillGovernanceSchemaRunner runner = new SkillGovernanceSchemaRunner(dataSource);
        runner.ensure();
        runner.ensure();

        assertEquals("keep-me", jdbc.queryForObject(
                "SELECT source FROM agentscope_skills WHERE name = 'meeting-booking'", String.class));
        assertEquals(1, count(jdbc, "ai_agent_skill"));
        assertEquals(9L, jdbc.queryForObject(
                "SELECT created_by FROM ai_agent_skill WHERE agent_id = 'session-chat'", Long.class));

        jdbc.update("""
                INSERT INTO agentscope_skills
                    (name, description, skill_content, source, status, visible_scope)
                VALUES ('later-skill', '后加的', '---', 'jdbc_agentscope_skills', '0', '0')
                """);
        runner.ensure();
        assertEquals(1, count(jdbc, "ai_agent_skill"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_skill b JOIN agentscope_skills s ON s.id = b.skill_id WHERE s.name = 'later-skill'",
                Integer.class));
    }

    @Test
    void v6AndV11ThenReadOnlyRepositoryKeepsRows() {
        DataSource dataSource = memory("v11");
        SkillTestSchema.create(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        users(jdbc);
        jdbc.update("INSERT INTO app_user (id, is_deleted, is_admin) VALUES (4, 0, 1)");
        try (JdbcAgentSkillRepository writable = new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource))) {
            assertTrue(writable.save(List.of(skill(writable, "meeting-booking", "正文和资料")), false));
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/mysql/V11__skill_governance.sql")).execute(dataSource);

        SkillGovernanceSchemaRunner runner = new SkillGovernanceSchemaRunner(dataSource);
        runner.ensure();
        runner.ensure();

        try (JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource), false)) {
            assertFalse(repo.isWriteable());
            assertEquals(SkillJdbc.SOURCE, repo.getSource());
            AgentSkill loaded = repo.getSkill("meeting-booking");
            assertEquals("正文和资料", loaded.getSkillContent());
            assertEquals("指南", loaded.getResource("references/guide.md"));
            assertEquals(SkillJdbc.SOURCE, loaded.getSource());
            int before = count(jdbc, "agentscope_skills");
            assertFalse(repo.save(List.of(skill(repo, "meeting-booking", "不该写进去")), true));
            assertEquals(before, count(jdbc, "agentscope_skills"));
            assertEquals("正文和资料", repo.getSkill("meeting-booking").getSkillContent());
        }
        assertEquals(1, count(jdbc, "ai_agent_skill"));
    }

    private static void users(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE app_user (
                    id BIGINT PRIMARY KEY,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    is_admin TINYINT NOT NULL DEFAULT 0
                )
                """);
    }

    private static AgentSkill skill(JdbcAgentSkillRepository repo, String name, String content) {
        return AgentSkill.builder()
                .name(name)
                .description("预约会议")
                .skillContent(content)
                .source(repo.getSource())
                .addResource("references/guide.md", "指南")
                .build();
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static DataSource memory(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + "-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }
}
