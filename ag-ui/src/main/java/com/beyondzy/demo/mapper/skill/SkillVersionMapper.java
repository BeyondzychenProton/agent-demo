package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.beyondzy.demo.domain.skill.SkillVersionBean;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 技能版本。序号和平台版本号的唯一约束由数据库兜底，服务层负责重试和条件更新。 */
@Mapper
public interface SkillVersionMapper extends BaseMapper<SkillVersionBean> {
    String COLUMNS = """
            id, skill_name, skill_id, version_no, package_version, platform_version, state, description,
            skill_content, metadata_json, source_rules_json, visible_scope, owner_dept_id, created_by,
            import_id, published_by, published_at, revision, gmt_create, gmt_modified, display_name
            """;

    @Select("SELECT " + COLUMNS + " FROM ai_skill_version WHERE id = #{id}")
    SkillVersionBean find(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM ai_skill_version WHERE id = #{id} FOR UPDATE")
    SkillVersionBean lock(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM ai_skill_version WHERE skill_name = #{name} AND state = 'DRAFT' "
            + "ORDER BY version_no DESC LIMIT 1 FOR UPDATE")
    SkillVersionBean latestDraftLocked(@Param("name") String name);

    @Select("SELECT id FROM ai_skill_version WHERE skill_name = #{name} FOR UPDATE")
    List<Long> lockBySkillName(@Param("name") String name);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM ai_skill_version WHERE skill_name = #{name}")
    int maxVersionNo(@Param("name") String name);

    @Select("SELECT COUNT(*) FROM ai_skill_version WHERE skill_name = #{name}")
    int countBySkillName(@Param("name") String name);

    @Select("SELECT COUNT(*) FROM ai_skill_version WHERE skill_name = #{name} AND version_no = 1")
    int countBaseline(@Param("name") String name);

    @Select("SELECT " + COLUMNS + " FROM ai_skill_version WHERE skill_name = #{name} "
            + "AND platform_version = #{platformVersion}")
    SkillVersionBean findByPlatform(@Param("name") String name, @Param("platformVersion") String platformVersion);

    @Select("""
            SELECT id, skill_name, skill_id, version_no, package_version, platform_version, state,
                   published_at, revision, description, visible_scope, owner_dept_id, created_by, display_name
            FROM ai_skill_version
            WHERE (#{name} IS NULL OR skill_name = #{name})
              AND (#{state} IS NULL OR state = #{state})
            ORDER BY skill_name, version_no DESC
            """)
    IPage<SkillVersionBean> page(IPage<SkillVersionBean> page, @Param("name") String name, @Param("state") String state);

    @Select("<script>SELECT " + COLUMNS + " FROM ai_skill_version WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<SkillVersionBean> listByIds(@Param("ids") Collection<Long> ids);

    @Select("""
            <script>
            SELECT id, skill_name, platform_version, version_no
            FROM ai_skill_version
            WHERE state = #{state}
              AND skill_name IN
            <foreach collection="names" item="name" open="(" separator="," close=")">#{name}</foreach>
            ORDER BY skill_name, version_no DESC
            </script>
            """)
    List<SkillVersionBean> listByNamesAndState(@Param("names") Collection<String> names, @Param("state") String state);

    @Select("""
            <script>
            SELECT id, platform_version, version_no
            FROM ai_skill_version
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<SkillVersionBean> findSummaries(@Param("ids") Collection<Long> ids);

    @Insert("""
            INSERT INTO ai_skill_version (
                skill_name, skill_id, version_no, package_version, platform_version, state, description,
                skill_content, metadata_json, source_rules_json, visible_scope, owner_dept_id, created_by,
                import_id, published_by, published_at, revision, gmt_create, gmt_modified, display_name)
            VALUES (
                #{skillName}, #{skillId}, #{versionNo}, #{packageVersion}, #{platformVersion}, #{state},
                #{description}, #{skillContent}, #{metadataJson}, #{sourceRulesJson}, #{visibleScope},
                #{ownerDeptId}, #{createdBy}, #{importId}, #{publishedBy}, #{publishedAt}, #{revision},
                #{gmtCreate}, #{gmtModified}, #{displayName})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertVersion(SkillVersionBean row);

    @Update("""
            UPDATE ai_skill_version
            SET description = #{description},
                display_name = #{displayName},
                skill_content = #{skillContent},
                metadata_json = #{metadataJson},
                visible_scope = #{visibleScope},
                owner_dept_id = #{ownerDeptId},
                revision = revision + 1,
                gmt_modified = #{gmtModified}
            WHERE id = #{id} AND revision = #{revision} AND state = 'DRAFT'
            """)
    int updateDraft(SkillVersionBean row);

    @Update("""
            UPDATE ai_skill_version
            SET state = 'PUBLISHED',
                platform_version = #{platformVersion},
                skill_id = #{skillId},
                published_by = #{publishedBy},
                published_at = #{publishedAt},
                revision = revision + 1,
                gmt_modified = #{gmtModified}
            WHERE id = #{id} AND revision = #{revision} AND state = 'DRAFT'
            """)
    int publish(SkillVersionBean row);
}
