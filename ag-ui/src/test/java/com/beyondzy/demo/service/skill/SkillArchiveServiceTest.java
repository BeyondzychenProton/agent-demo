package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.beyondzy.demo.config.SkillJdbc;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.service.skill.impl.SkillCatalogServiceImpl;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** zip 与相对路径上传：附属文件落资源表，穿越和二进制不入库。 */
class SkillArchiveServiceTest {
    private static final String MARKDOWN = """
            ---
            name: meeting-booking
            description: 预约会议
            ---
            # 会议
            """;

    @Test
    void zipWithWrapperBecomesOneSkillAndResourceRows() {
        try (Fixture fixture = Fixture.open()) {
            fixture.service.saveArchive(zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/references/guide.md", "指南",
                    "meeting-booking/scripts/echo.sh", "echo hi"), false);

            AgentSkill stored = fixture.repo.getSkill("meeting-booking");
            assertEquals(MARKDOWN, stored.getSkillContent());
            assertEquals("指南", stored.getResource("references/guide.md"));
            assertEquals("echo hi", stored.getResource("scripts/echo.sh"));
            assertEquals(2, fixture.service.list(1, 20).items().get(0).resourceCount());
            assertEquals(List.of("references/guide.md", "scripts/echo.sh"),
                    fixture.service.detail("meeting-booking", false).resources().stream()
                            .map(SkillView.SkillResource::path).toList());
        }
    }

    @Test
    void flatZipAndRelativeMultipartUseTheSameRules() {
        try (Fixture fixture = Fixture.open()) {
            fixture.service.saveArchive(zip(
                    "SKILL.md", MARKDOWN,
                    "references/guide.md", "指南"), false);
            assertEquals("指南", fixture.repo.getSkill("meeting-booking").getResource("references/guide.md"));
            fixture.service.delete("meeting-booking");

            fixture.service.saveFiles(List.of(
                    new SkillUpload("meeting-booking/SKILL.md", MARKDOWN.getBytes(StandardCharsets.UTF_8)),
                    new SkillUpload("meeting-booking/references/guide.md", "另一份".getBytes(StandardCharsets.UTF_8)),
                    new SkillUpload("meeting-booking/scripts/echo.sh", "echo".getBytes(StandardCharsets.UTF_8))), false);
            AgentSkill stored = fixture.repo.getSkill("meeting-booking");
            assertEquals("另一份", stored.getResource("references/guide.md"));
            assertEquals("echo", stored.getResource("scripts/echo.sh"));
        }
    }

    @Test
    void traversalBinaryAndDisallowedDirectoriesAreNotStored() {
        try (Fixture fixture = Fixture.open()) {
            assertEquals(400, status(fixture, zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/../outside.md", "no")));
            assertEquals(400, status(fixture, zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/scripts/bin.sh", new byte[] {(byte) 0xff})));
            assertEquals(400, status(fixture, zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/reference/guide.md", "no")));
            assertEquals(400, status(fixture, zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/examples/guide.md", "no")));
            assertEquals(400, status(fixture, zip("C:/SKILL.md", MARKDOWN)));
            assertEquals(400, status(fixture, zip("/tmp/SKILL.md", MARKDOWN)));
            assertEquals(400, status(fixture.service, List.of(
                    new SkillUpload("../SKILL.md", MARKDOWN.getBytes(StandardCharsets.UTF_8)))));
            assertEquals(0, fixture.service.list(1, 20).total());
        }
    }

    @Test
    void overwriteReplacesResources() {
        try (Fixture fixture = Fixture.open()) {
            fixture.service.saveArchive(zip(
                    "SKILL.md", MARKDOWN,
                    "scripts/echo.sh", "echo hi"), false);
            fixture.service.saveArchive(zip(
                    "SKILL.md", MARKDOWN,
                    "references/guide.md", "指南"), true);
            AgentSkill stored = fixture.repo.getSkill("meeting-booking");
            assertEquals("指南", stored.getResource("references/guide.md"));
            assertEquals(null, stored.getResource("scripts/echo.sh"));
            assertEquals(1, fixture.service.list(1, 20).items().get(0).resourceCount());
        }
    }

    private static int status(Fixture fixture, byte[] zip) {
        return assertThrows(SkillException.class, () -> fixture.service.saveArchive(zip, false)).status();
    }

    private static int status(SkillCatalogService service, List<SkillUpload> files) {
        return assertThrows(SkillException.class, () -> service.saveFiles(files, false)).status();
    }

    private static byte[] zip(Object... nameAndBody) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (int i = 0; i < nameAndBody.length; i += 2) {
                    zip.putNextEntry(new ZipEntry((String) nameAndBody[i]));
                    Object body = nameAndBody[i + 1];
                    byte[] bytes = body instanceof byte[] raw ? raw : ((String) body).getBytes(StandardCharsets.UTF_8);
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JdbcAgentSkillRepository repo;
        final SqlSession session;
        final SkillCatalogService service;

        private Fixture(JdbcAgentSkillRepository repo, SqlSession session, SkillCatalogService service) {
            this.repo = repo;
            this.session = session;
            this.service = service;
        }

        static Fixture open() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:archive-" + System.nanoTime()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            dataSource.setUser("sa");
            JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource));
            MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                    "archive-test", new JdbcTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.addMapper(SkillMapper.class);
            configuration.addMapper(SkillResourceMapper.class);
            SqlSessionFactory factory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
            SqlSession session = factory.openSession(true);
            return new Fixture(repo, session, new SkillCatalogServiceImpl(
                    repo, session.getMapper(SkillMapper.class), session.getMapper(SkillResourceMapper.class)));
        }

        @Override
        public void close() {
            session.close();
            repo.close();
        }
    }
}
