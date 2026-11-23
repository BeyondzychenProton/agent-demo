package com.beyondzy.demo.service.skill;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.beyondzy.demo.config.SkillTestSchema;
import com.beyondzy.demo.config.mybatis.MybatisPlusMetaObjectHandler;
import com.beyondzy.demo.config.skill.SkillGovernanceSchemaRunner;
import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.mapper.directory.DirectoryDepartmentMapper;
import com.beyondzy.demo.mapper.directory.DirectoryUserMapper;
import com.beyondzy.demo.mapper.skill.AgentSkillMapper;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.mapper.user.UserMapper;
import com.beyondzy.demo.service.skill.impl.SkillAdminServiceImpl;
import com.beyondzy.demo.service.skill.impl.SkillVisibilityServiceImpl;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 管理端技能测试的 H2：V6、治理列、通讯录和带审计填充的 Mapper。 */
final class SkillAdminHarness implements AutoCloseable {
    final JdbcTemplate jdbc;
    final SkillAdminService admin;
    final SkillVisibilityService visibility;
    final JdbcAgentSkillRepository repository;
    private final SqlSession session;

    private SkillAdminHarness(
            JdbcTemplate jdbc,
            SkillAdminService admin,
            SkillVisibilityService visibility,
            JdbcAgentSkillRepository repository,
            SqlSession session) {
        this.jdbc = jdbc;
        this.admin = admin;
        this.visibility = visibility;
        this.repository = repository;
        this.session = session;
    }

    static SkillAdminHarness open() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:admin-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        SkillTestSchema.create(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        directory(jdbc);
        new SkillGovernanceSchemaRunner(dataSource).ensure();
        SqlSession session = session(dataSource);
        SkillVisibilityService visibility = new SkillVisibilityServiceImpl(
                session.getMapper(UserMapper.class),
                session.getMapper(DirectoryUserMapper.class),
                session.getMapper(DirectoryDepartmentMapper.class),
                session.getMapper(AgentSkillMapper.class));
        SkillAdminService admin = new SkillAdminServiceImpl(
                session.getMapper(SkillMapper.class),
                session.getMapper(SkillResourceMapper.class),
                session.getMapper(AgentSkillMapper.class),
                visibility);
        return new SkillAdminHarness(jdbc, admin, visibility, readOnly(dataSource), session);
    }

    void user(long id, Long directoryId, long departmentId, boolean admin) {
        jdbc.update("INSERT INTO app_user (id, is_deleted, is_admin, mantianxing_user_id) VALUES (?, 0, ?, ?)",
                id, admin ? 1 : 0, directoryId);
        if (directoryId != null) {
            jdbc.update("""
                    INSERT INTO app_directory_user (source_id, department_id, status, is_deleted)
                    VALUES (?, ?, '0', 0)
                    """, directoryId, departmentId);
        }
    }

    void department(long id, Long parentId, String name) {
        jdbc.update("""
                INSERT INTO app_directory_department (source_id, parent_id, name, status, is_deleted)
                VALUES (?, ?, ?, '0', 0)
                """, id, parentId, name);
    }

    int bindings() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_skill", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void close() {
        session.close();
        repository.close();
    }

    private static void directory(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE app_user (
                    id BIGINT PRIMARY KEY,
                    is_deleted INT NOT NULL DEFAULT 0,
                    is_admin INT NOT NULL DEFAULT 0,
                    mantianxing_user_id BIGINT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE app_directory_department (
                    source_id BIGINT PRIMARY KEY,
                    parent_id BIGINT NULL,
                    name VARCHAR(30) NULL,
                    status CHAR(1) NULL,
                    is_deleted INT NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE app_directory_user (
                    source_id BIGINT PRIMARY KEY,
                    department_id BIGINT NULL,
                    status CHAR(1) NULL,
                    is_deleted INT NOT NULL DEFAULT 0
                )
                """);
    }

    private static SqlSession session(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(new Environment(
                "skill-admin", new JdbcTransactionFactory(), dataSource));
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        globalConfig.setMetaObjectHandler(new MybatisPlusMetaObjectHandler());
        globalConfig.setDbConfig(new GlobalConfig.DbConfig());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(SkillMapper.class);
        configuration.addMapper(SkillResourceMapper.class);
        configuration.addMapper(AgentSkillMapper.class);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(DirectoryUserMapper.class);
        configuration.addMapper(DirectoryDepartmentMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        return factory.openSession(true);
    }

    private static JdbcAgentSkillRepository readOnly(DataSource dataSource) {
        return new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource), false);
    }
}
