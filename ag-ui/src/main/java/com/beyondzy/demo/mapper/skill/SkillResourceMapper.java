package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.skill.SkillResourceBean;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 技能资源查询。删除随技能级联（表上有 ON DELETE CASCADE），无需单独删。 */
@Mapper
public interface SkillResourceMapper extends BaseMapper<SkillResourceBean> {

    @Select("""
            SELECT resource_path
            FROM agentscope_skill_resources
            WHERE id = #{id}
            ORDER BY resource_path
            """)
    List<String> findPaths(@Param("id") long id);

    /** 一次取回资源正文（运行时加载技能用）。 */
    @Select("""
            SELECT id, resource_path, resource_content
            FROM agentscope_skill_resources
            WHERE id = #{id}
            ORDER BY resource_path
            """)
    List<SkillResourceBean> findContents(@Param("id") long id);
}
