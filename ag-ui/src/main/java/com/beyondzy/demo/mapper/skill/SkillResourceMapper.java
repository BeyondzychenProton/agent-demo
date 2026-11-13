package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.skill.SkillResourceBean;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 技能资源路径查询。不读取 resource_content。 */
@Mapper
public interface SkillResourceMapper extends BaseMapper<SkillResourceBean> {
    @Select("""
            SELECT resource_path
            FROM agentscope_skill_resources
            WHERE id = #{id}
            ORDER BY resource_path
            """)
    List<String> findPaths(@Param("id") long id);
}
