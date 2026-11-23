package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.skill.SkillBean;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 技能目录查询。不读取 skill_content。写入不走本 Mapper。 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillBean> {
    @Select("""
            SELECT id, name, description, source, created_at, updated_at,
                   (SELECT COUNT(*) FROM agentscope_skill_resources r WHERE r.id = s.id) AS resource_count
            FROM agentscope_skills s
            ORDER BY name
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SkillBean> page(@Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM agentscope_skills")
    long countAll();

    @Select("""
            SELECT id, name, description, source, created_at, updated_at
            FROM agentscope_skills
            WHERE name = #{name}
            """)
    SkillBean findSummary(@Param("name") String name);

    /** 管理端详情和保存前的整行。包含正文，列表不要走这个方法。 */
    @Select("""
            SELECT id, name, description, skill_content, source, metadata_json, status, visible_scope,
                   owner_dept_id, create_by, created_at, updated_at,
                   (SELECT name FROM app_directory_department d WHERE d.source_id = s.owner_dept_id) AS owner_dept_name,
                   (SELECT COUNT(*) FROM agentscope_skill_resources r WHERE r.id = s.id) AS resource_count,
                   (SELECT COUNT(*) FROM ai_agent_skill b
                     WHERE b.skill_id = s.id AND b.agent_id = 'session-chat') AS bound
            FROM agentscope_skills s
            WHERE name = #{name}
            """)
    SkillBean findGoverned(@Param("name") String name);

    /** ESCAPE 用 !，避免反斜杠让 MySQL 预编译把后面的占位符吃进字符串。 */
    @Select("""
            SELECT id, name, description, status, visible_scope, owner_dept_id, create_by, updated_at,
                   (SELECT name FROM app_directory_department d WHERE d.source_id = s.owner_dept_id) AS owner_dept_name,
                   (SELECT COUNT(*) FROM agentscope_skill_resources r WHERE r.id = s.id) AS resource_count,
                   (SELECT COUNT(*) FROM ai_agent_skill b
                     WHERE b.skill_id = s.id AND b.agent_id = 'session-chat') AS bound
            FROM agentscope_skills s
            WHERE (#{q} IS NULL OR name LIKE CONCAT('%', #{q}, '%') ESCAPE '!'
                   OR description LIKE CONCAT('%', #{q}, '%') ESCAPE '!')
              AND (#{status} IS NULL OR status = #{status})
            ORDER BY name
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SkillBean> adminPage(@Param("q") String query, @Param("status") String status,
            @Param("limit") int limit, @Param("offset") long offset);

    /** 筛选条件与 adminPage 相同，转义符同样是 !。 */
    @Select("""
            SELECT COUNT(*)
            FROM agentscope_skills
            WHERE (#{q} IS NULL OR name LIKE CONCAT('%', #{q}, '%') ESCAPE '!'
                   OR description LIKE CONCAT('%', #{q}, '%') ESCAPE '!')
              AND (#{status} IS NULL OR status = #{status})
            """)
    long adminCount(@Param("q") String query, @Param("status") String status);

    /** 绑定页候选项：启用，并且对当前操作人可见。不要求已经绑定。 */
    @Select("""
            SELECT id, name, description, status, visible_scope, owner_dept_id, create_by
            FROM agentscope_skills
            WHERE status = '0'
            ORDER BY name
            """)
    List<SkillBean> listEnabled();

    @Insert("""
            INSERT INTO agentscope_skills
                (name, description, skill_content, source, metadata_json, status, visible_scope, owner_dept_id, create_by)
            VALUES
                (#{name}, #{description}, #{skillContent}, #{source}, #{metadataJson}, #{status}, #{visibleScope},
                 #{ownerDeptId}, #{createBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertGovernance(SkillBean row);

    /** 不改 name、source、created_at、create_by。updated_at 仍由列上的自动更新维护。 */
    @Update("""
            UPDATE agentscope_skills
            SET description = #{description},
                skill_content = #{skillContent},
                metadata_json = #{metadataJson},
                status = #{status},
                visible_scope = #{visibleScope},
                owner_dept_id = #{ownerDeptId}
            WHERE name = #{name}
            """)
    int updateGovernance(SkillBean row);

    @Update("UPDATE agentscope_skills SET status = #{status} WHERE name = #{name}")
    int updateStatus(@Param("name") String name, @Param("status") String status);
}
