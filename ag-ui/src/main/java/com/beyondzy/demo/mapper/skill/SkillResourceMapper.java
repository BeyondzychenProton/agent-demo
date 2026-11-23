package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.skill.SkillResourceBean;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 技能资源。列表只查路径；管理端详情才读取正文。 */
@Mapper
public interface SkillResourceMapper extends BaseMapper<SkillResourceBean> {
    @Select("""
            SELECT resource_path
            FROM agentscope_skill_resources
            WHERE id = #{id}
            ORDER BY resource_path
            """)
    List<String> findPaths(@Param("id") long id);

    @Select("""
            SELECT resource_path, resource_content
            FROM agentscope_skill_resources
            WHERE id = #{id}
            ORDER BY resource_path
            """)
    List<SkillResourceBean> findContents(@Param("id") long id);

    @Delete("DELETE FROM agentscope_skill_resources WHERE id = #{id}")
    int deleteBySkillId(@Param("id") long id);

    @Insert("""
            INSERT INTO agentscope_skill_resources (id, resource_path, resource_content)
            VALUES (#{id}, #{path}, #{content})
            """)
    int insertResource(@Param("id") long id, @Param("path") String path, @Param("content") String content);
}
