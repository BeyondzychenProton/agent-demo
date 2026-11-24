package com.beyondzy.demo.mapper.skill;

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
}
