package com.beyondzy.demo.mapper.skill;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 生效版本必须显式记录，不通过版本排序猜测。 */
@Mapper
public interface SkillReleaseMapper {
    @Select("SELECT current_version_id FROM ai_skill_release WHERE skill_id = #{skillId}")
    Long current(@Param("skillId") long skillId);

    @Insert("""
            INSERT INTO ai_skill_release (skill_id, current_version_id) VALUES (#{skillId}, #{versionId})
            ON DUPLICATE KEY UPDATE current_version_id = #{versionId}
            """)
    int point(@Param("skillId") long skillId, @Param("versionId") long versionId);

    @Select("""
            <script>
            SELECT skill_id AS skillId, current_version_id AS currentVersionId
            FROM ai_skill_release
            WHERE skill_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<ReleaseCurrent> currents(@Param("ids") Collection<Long> ids);

    record ReleaseCurrent(Long skillId, Long currentVersionId) {}
}
