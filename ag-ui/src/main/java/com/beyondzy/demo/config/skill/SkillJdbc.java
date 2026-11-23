package com.beyondzy.demo.config.skill;

import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import javax.sql.DataSource;

/**
 * 组装官方 JDBC 技能方言。
 *
 * <p>基础表必须保持关闭：本应用已有自己的会话表，不能让方言再建一套。
 */
public final class SkillJdbc {
    public static final String SKILL_TABLE = "agentscope_skills";
    public static final String RESOURCE_TABLE = "agentscope_skill_resources";
    /** 框架用表名拼出的 source。管理端插入时写这个值，之后不改，也不表示创建人。 */
    public static final String SOURCE = "jdbc_agentscope_skills";
    /** 当前唯一会跑聊天的智能体。展示名是「海卓大脑办公助手」。 */
    public static final String SESSION_AGENT = "session-chat";

    private SkillJdbc() {}

    public static AbstractJdbcDialect dialect(DataSource dataSource) {
        return AbstractJdbcDialect.from(dataSource)
                .enableBaseTables(false)
                .enableSkillTables(true)
                .skillTableName(SKILL_TABLE)
                .skillResourcesTableName(RESOURCE_TABLE)
                .autoCreateTable(false)
                .build();
    }
}
