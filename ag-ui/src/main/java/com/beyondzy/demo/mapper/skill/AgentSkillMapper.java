package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.skill.AgentSkillBean;
import com.beyondzy.demo.domain.skill.SkillBean;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 智能体与技能的绑定。插入走 BaseMapper，让 gmtCreate 由审计填充写入。 */
@Mapper
public interface AgentSkillMapper extends BaseMapper<AgentSkillBean> {
    @Select("SELECT COUNT(*) FROM ai_agent_skill WHERE skill_id = #{skillId}")
    int countBySkill(@Param("skillId") long skillId);

    @Delete("DELETE FROM ai_agent_skill WHERE agent_id = #{agentId}")
    int deleteByAgent(@Param("agentId") String agentId);

    @Select("""
            SELECT s.id, s.name, s.description, s.status, s.visible_scope, s.owner_dept_id, s.create_by, b.version_id
            FROM ai_agent_skill b
            JOIN agentscope_skills s ON s.id = b.skill_id
            WHERE b.agent_id = #{agentId}
            ORDER BY s.name
            """)
    List<SkillBean> listBound(@Param("agentId") String agentId);

    @Update("UPDATE ai_agent_skill SET version_id = #{versionId} WHERE skill_id = #{skillId} AND version_id IS NULL")
    int pinMissing(@Param("skillId") long skillId, @Param("versionId") long versionId);

    @Insert("""
            <script>
            INSERT INTO ai_agent_skill (agent_id, skill_id, version_id, created_by, gmt_create, gmt_modified)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.agentId}, #{row.skillId}, #{row.versionId}, #{row.createdBy}, #{now}, #{now})
            </foreach>
            </script>
            """)
    int insertBindings(@Param("rows") List<AgentSkillBean> rows, @Param("now") java.time.LocalDateTime now);

    @Update("""
            <script>
            UPDATE ai_agent_skill
            SET version_id = CASE skill_id
            <foreach collection="rows" item="row">
                WHEN #{row.skillId} THEN #{row.versionId}
            </foreach>
            END
            WHERE version_id IS NULL
              AND skill_id IN
            <foreach collection="rows" item="row" open="(" separator="," close=")">#{row.skillId}</foreach>
            </script>
            """)
    int pinMissingAll(@Param("rows") List<VersionPin> rows);

    record VersionPin(long skillId, long versionId) {}
}
