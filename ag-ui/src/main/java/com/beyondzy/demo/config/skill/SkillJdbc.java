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

    private SkillJdbc() {}

    public static AbstractJdbcDialect dialect(DataSource dataSource) {
        return AbstractJdbcDialect.from(dataSource)
                .enableBaseTables(false)
                .enableSkillTables(true)
                .skillTableName(SKILL_TABLE)
                .skillResourcesTableName(RESOURCE_TABLE)
                .autoCreateTable(true)
                .build();
    }
}
