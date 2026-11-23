package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.beyondzy.demo.config.SkillTestSchema;
import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.service.skill.impl.SkillCatalogServiceImpl;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** 旧目录的写入已经停用。解析规则仍由 SkillDocuments 负责。 */
class SkillCatalogServiceTest {

    @Test
    void writesAreGone() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:retired-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        SkillTestSchema.create(dataSource);
        try (JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource));
                SqlSession session = session(dataSource)) {
            SkillCatalogService service = new SkillCatalogServiceImpl(
                    repo, session.getMapper(SkillMapper.class), session.getMapper(SkillResourceMapper.class));
            byte[] body = "---\nname: meeting-booking\ndescription: 预约\n---\n# 会议\n".getBytes(StandardCharsets.UTF_8);
            assertEquals(410, assertThrows(SkillException.class, () -> service.saveMarkdown(body, false)).status());
            assertEquals(410, assertThrows(SkillException.class, () -> service.saveArchive(body, false)).status());
            assertEquals(410, assertThrows(SkillException.class,
                    () -> service.saveFiles(List.of(new SkillUpload("SKILL.md", body)), false)).status());
            assertEquals(410, assertThrows(SkillException.class, () -> service.delete("meeting-booking")).status());
            assertEquals("写入口已收到管理端",
                    assertThrows(SkillException.class, () -> service.saveMarkdown(body, true)).getMessage());
        }
    }

    @Test
    void documentsStillRejectInvalidMarkdown() {
        assertEquals(400, status("""
                ---
                name: Meeting
                description: 大写不行
                ---
                # x
                """));
        assertEquals(400, status("# 没有 frontmatter\n"));
    }

    private static int status(String markdown) {
        return assertThrows(SkillException.class,
                () -> SkillDocuments.markdown(markdown.getBytes(StandardCharsets.UTF_8))).status();
    }

    private static SqlSession session(javax.sql.DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                "retired", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(SkillMapper.class);
        configuration.addMapper(SkillResourceMapper.class);
        SqlSessionFactory factory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        return factory.openSession(true);
    }
}
