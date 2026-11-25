package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.beyondzy.demo.domain.skill.SkillManagementRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 按逻辑技能去重，草稿集合允许与启停集合重叠。依赖统计不使用参考文件数或 Agent 数。 */
@Mapper
public interface SkillManagementMapper {
    String CATALOG = """
            SELECT n.name,
              CASE WHEN #{state} = 'draft' OR s.id IS NULL THEN d.display_name ELSE v.display_name END AS display_name,
              CASE WHEN #{state} = 'draft' OR s.id IS NULL THEN d.description ELSE s.description END AS description,
              s.status,
              CASE WHEN #{state} = 'draft' OR s.id IS NULL THEN d.visible_scope ELSE s.visible_scope END AS visible_scope,
              COALESCE(s.create_by, d.created_by) AS owner_id, u.name AS owner_name,
              CASE WHEN #{state} = 'draft' OR s.id IS NULL THEN d.gmt_modified ELSE s.updated_at END AS updated_at,
              v.id AS current_version_id, v.platform_version, v.version_no, d.id AS draft_version_id, d.version_no AS draft_version_no,
              COALESCE(dep.total, 0) AS dependency_count, COALESCE(dep.mapped, 0) AS mapped_count,
              (SELECT COUNT(*) FROM ai_agent_skill b WHERE b.skill_id = s.id) AS bound_count
            FROM (SELECT name FROM agentscope_skills UNION SELECT skill_name AS name FROM ai_skill_version WHERE state = 'DRAFT') n
            LEFT JOIN agentscope_skills s ON s.name = n.name
            LEFT JOIN ai_skill_release r ON r.skill_id = s.id
            LEFT JOIN ai_skill_version v ON v.id = r.current_version_id AND v.state = 'PUBLISHED' AND v.skill_id = s.id
            LEFT JOIN ai_skill_version d ON d.id = (
              SELECT x.id FROM ai_skill_version x WHERE x.skill_name = n.name AND x.state = 'DRAFT' ORDER BY x.version_no DESC LIMIT 1)
            LEFT JOIN app_user u ON u.id = COALESCE(s.create_by, d.created_by)
            LEFT JOIN (SELECT version_id, COUNT(*) AS total,
                SUM(CASE WHEN mapping_state = 'CONFIRMED' AND target_kind <> 'UNRESOLVED' THEN 1 ELSE 0 END) AS mapped
                FROM ai_skill_version_dependency GROUP BY version_id) dep
              ON dep.version_id = CASE WHEN #{state} = 'draft' OR s.id IS NULL THEN d.id ELSE v.id END
            WHERE (#{q} IS NULL OR n.name LIKE CONCAT('%', #{q}, '%') ESCAPE '!'
              OR s.description LIKE CONCAT('%', #{q}, '%') ESCAPE '!'
              OR d.description LIKE CONCAT('%', #{q}, '%') ESCAPE '!'
              OR v.display_name LIKE CONCAT('%', #{q}, '%') ESCAPE '!'
              OR d.display_name LIKE CONCAT('%', #{q}, '%') ESCAPE '!'
              OR u.name LIKE CONCAT('%', #{q}, '%') ESCAPE '!')
              AND (#{state} = 'all' OR (#{state} = 'enabled' AND s.status = '0')
                OR (#{state} = 'disabled' AND s.status = '1') OR (#{state} = 'draft' AND d.id IS NOT NULL))
            """;
    String RESOURCE_FILTER = """
            WHERE (#{resource} = 'all'
              OR (#{resource} = 'pending' AND t.dependency_count > t.mapped_count)
              OR (#{resource} = 'mapped' AND t.dependency_count = t.mapped_count AND
                (#{state} = 'draft' OR t.current_version_id IS NULL OR t.platform_version IS NOT NULL)))
            """;

    @Select("SELECT * FROM (" + CATALOG + ") t " + RESOURCE_FILTER + " ORDER BY name")
    IPage<SkillManagementRow> page(IPage<SkillManagementRow> page, @Param("q") String query, @Param("state") String state,
            @Param("resource") String resource);

    @Select("SELECT COUNT(*) FROM (" + CATALOG + ") t " + RESOURCE_FILTER)
    long count(@Param("q") String query, @Param("state") String state, @Param("resource") String resource);

    @Select("SELECT * FROM (" + CATALOG + ") t WHERE name = #{name}")
    SkillManagementRow exact(@Param("q") String query, @Param("state") String state, @Param("name") String name);
}
