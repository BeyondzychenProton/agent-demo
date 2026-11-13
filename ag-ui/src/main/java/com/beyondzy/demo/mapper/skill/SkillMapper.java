package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.skill.SkillBean;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 技能目录查询/删除。列表查询不读取 skill_content；正文只在详情与运行时加载时读取。
 *
 * <p>可见性规则：平台技能 {@code owner_user_id = 0} 对所有人可见；用户技能仅本人可见。
 * 同名时用户层优先（唯一键是 (owner_user_id, name)，两层可各存一份）。
 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillBean> {

    /** 可见技能分页（平台 + 本人），不读正文。 */
    @Select("""
            SELECT id, name, description, source, owner_user_id, created_at, updated_at,
                   (SELECT COUNT(*) FROM agentscope_skill_resources r WHERE r.id = s.id) AS resource_count
            FROM agentscope_skills s
            WHERE s.owner_user_id = 0 OR s.owner_user_id = #{userId}
            ORDER BY s.name
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SkillBean> pageVisible(
            @Param("userId") long userId, @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM agentscope_skills WHERE owner_user_id = 0 OR owner_user_id = #{userId}")
    long countVisible(@Param("userId") long userId);

    /** 管理员全量分页，不读正文。 */
    @Select("""
            SELECT id, name, description, source, owner_user_id, created_at, updated_at,
                   (SELECT COUNT(*) FROM agentscope_skill_resources r WHERE r.id = s.id) AS resource_count
            FROM agentscope_skills s
            ORDER BY s.owner_user_id, s.name
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SkillBean> pageAll(@Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM agentscope_skills")
    long countAll();

    /** 可见范围内的完整技能行（含正文），同名时用户层优先。 */
    @Select("""
            SELECT id, name, description, skill_content, source, owner_user_id, metadata_json,
                   created_at, updated_at
            FROM agentscope_skills
            WHERE name = #{name} AND (owner_user_id = 0 OR owner_user_id = #{userId})
            ORDER BY owner_user_id DESC
            LIMIT 1
            """)
    SkillBean findVisibleFull(
            @Param("userId") long userId, @Param("name") String name);

    /** 指归属的完整技能行（管理员详情 / 删除定位）。 */
    @Select("""
            SELECT id, name, description, skill_content, source, owner_user_id, metadata_json,
                   created_at, updated_at
            FROM agentscope_skills
            WHERE owner_user_id = #{ownerUserId} AND name = #{name}
            """)
    SkillBean findByOwnerFull(
            @Param("ownerUserId") long ownerUserId, @Param("name") String name);

    /** 可见范围内的全部技能行（运行时加载给 Agent）。 */
    @Select("""
            SELECT id, name, description, skill_content, source, owner_user_id, metadata_json,
                   created_at, updated_at
            FROM agentscope_skills
            WHERE owner_user_id = 0 OR owner_user_id = #{userId}
            ORDER BY name, owner_user_id DESC
            """)
    List<SkillBean> listVisibleFull(@Param("userId") long userId);

    @Delete("DELETE FROM agentscope_skills WHERE owner_user_id = #{ownerUserId} AND name = #{name}")
    int deleteByOwner(
            @Param("ownerUserId") long ownerUserId, @Param("name") String name);
}
