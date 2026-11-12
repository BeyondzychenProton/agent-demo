package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.skill.SkillBean;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
