package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.config.SkillTestSchema;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.service.skill.impl.SkillCatalogServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;

/** 技能测试夹具：H2(MySQL 模式) + 建表 + MyBatis + 自管 SkillStore。 */
public final class SkillTestSupport {

    private SkillTestSupport() {}

    public static DataSource dataSource(String prefix) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + prefix + "-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        SkillTestSchema.create(dataSource);
        return dataSource;
    }

    public static SqlSession session(String name, DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment(name, new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(SkillMapper.class);
        configuration.addMapper(SkillResourceMapper.class);
        SqlSessionFactory factory =
                new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        return factory.openSession(true);
    }

    /** 一套可用的技能存储 + 服务。 */
    public static final class Fixture implements AutoCloseable {
        public final SqlSession session;
        public final SkillStore store;
        public final SkillCatalogService service;

        private Fixture(SqlSession session, SkillStore store, SkillCatalogService service) {
            this.session = session;
            this.store = store;
            this.service = service;
        }

        public static Fixture open(String name) {
            DataSource dataSource = dataSource(name);
            SqlSession session = session(name, dataSource);
            SkillStore store = new SkillStore(
                    session.getMapper(SkillMapper.class),
                    session.getMapper(SkillResourceMapper.class),
                    new ObjectMapper());
            return new Fixture(session, store, new SkillCatalogServiceImpl(store));
        }

        @Override
        public void close() {
            session.close();
        }
    }
}
