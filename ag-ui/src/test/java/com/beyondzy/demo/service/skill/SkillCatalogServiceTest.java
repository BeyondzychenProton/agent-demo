package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.config.SkillTestSchema;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.service.skill.impl.SkillCatalogServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** 单个 SKILL.md 的保存、列表、详情和删除。列表查询不读取正文。 */
class SkillCatalogServiceTest {
    private static final String MARKDOWN = """
            ---
            name: meeting-booking
            description: 预约会议
            license: MIT
            ---
            # 会议
            """;

    @Test
    void savesListDetailAndDeleteWithoutLoadingBodiesOnList() throws Exception {
        RecordingDataSource dataSource = database();
        try (JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource));
                SqlSession session = session(dataSource)) {
            SkillCatalogService service = service(repo, session);
            service.saveMarkdown(MARKDOWN.getBytes(StandardCharsets.UTF_8), false);

            AgentSkill stored = repo.getSkill("meeting-booking");
            assertEquals(MARKDOWN, stored.getSkillContent());
            assertEquals(repo.getSource(), stored.getSource());
            assertEquals("MIT", String.valueOf(stored.getMetadata().get("license")));
            assertEquals("预约会议", stored.getDescription());

            dataSource.sql.clear();
            SkillPage page = service.list(1, 20);
            assertEquals(1, page.total());
            assertEquals("meeting-booking", page.items().get(0).name());
            assertEquals("预约会议", page.items().get(0).description());
            assertEquals(0, page.items().get(0).resourceCount());
            assertNotNull(page.items().get(0).createdAt());
            assertNotNull(page.items().get(0).updatedAt());
            assertTrue(dataSource.sql.stream().anyMatch(sql -> sql.toLowerCase().contains("from agentscope_skills")));
            assertFalse(dataSource.sql.stream()
                    .filter(sql -> sql.toLowerCase().contains("from agentscope_skills"))
                    .anyMatch(sql -> sql.toLowerCase().contains("skill_content")));

            SkillView hidden = service.detail("meeting-booking", false);
            assertNull(hidden.skillContent());
            assertTrue(hidden.resources().isEmpty());
            assertEquals(MARKDOWN, service.detail("meeting-booking", true).skillContent());

            service.delete("meeting-booking");
            SkillException missing = assertThrows(SkillException.class,
                    () -> service.detail("meeting-booking", false));
            assertEquals(404, missing.status());
        }
    }

    @Test
    void duplicateWithoutOverwriteIsConflictAndOverwriteReplaces() {
        RecordingDataSource dataSource = database();
        try (JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource));
                SqlSession session = session(dataSource)) {
            SkillCatalogService service = service(repo, session);
            byte[] first = MARKDOWN.getBytes(StandardCharsets.UTF_8);
            service.saveMarkdown(first, false);
            SkillException conflict = assertThrows(SkillException.class, () -> service.saveMarkdown(first, false));
            assertEquals(409, conflict.status());

            String replaced = MARKDOWN.replace("预约会议", "改过的说明");
            service.saveMarkdown(replaced.getBytes(StandardCharsets.UTF_8), true);
            assertEquals("改过的说明", repo.getSkill("meeting-booking").getDescription());
            assertEquals(replaced, repo.getSkill("meeting-booking").getSkillContent());
        }
    }

    @Test
    void rejectsInvalidNameOversizedAndBinary() {
        RecordingDataSource dataSource = database();
        try (JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource));
                SqlSession session = session(dataSource)) {
            SkillCatalogService service = service(repo, session);
            assertEquals(400, status(service, """
                    ---
                    name: Meeting
                    description: 大写不行
                    ---
                    # x
                    """.getBytes(StandardCharsets.UTF_8)));
            assertEquals(400, status(service, """
                    # 没有 frontmatter
                    """.getBytes(StandardCharsets.UTF_8)));

            byte[] oversized = new byte[(256 * 1024) + 1];
            Arrays.fill(oversized, (byte) 'a');
            assertEquals(400, status(service, oversized));
            assertEquals(400, status(service, new byte[] {(byte) 0xff, (byte) 0xfe}));
            assertEquals(400, status(service, "---\nname: ok\0\ndescription: x\n---\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals(0, service.list(1, 20).total());
        }
    }

    private static int status(SkillCatalogService service, byte[] body) {
        return assertThrows(SkillException.class, () -> service.saveMarkdown(body, false)).status();
    }

    private static SkillCatalogService service(JdbcAgentSkillRepository repo, SqlSession session) {
        return new SkillCatalogServiceImpl(repo, session.getMapper(SkillMapper.class),
                session.getMapper(SkillResourceMapper.class));
    }

    private static SqlSession session(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                "skill-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(SkillMapper.class);
        configuration.addMapper(SkillResourceMapper.class);
        SqlSessionFactory factory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        return factory.openSession(true);
    }

    private static RecordingDataSource database() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:catalog-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        SkillTestSchema.create(dataSource);
        return new RecordingDataSource(dataSource);
    }

    private static final class RecordingDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        final List<String> sql = new ArrayList<>();

        RecordingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }

        private Connection wrap(Connection raw) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return method.invoke(raw, args);
                if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                        && args[0] instanceof String text) {
                    sql.add(text);
                }
                try {
                    return method.invoke(raw, args);
                } catch (InvocationTargetException error) {
                    throw error.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, handler);
        }
    }
}
