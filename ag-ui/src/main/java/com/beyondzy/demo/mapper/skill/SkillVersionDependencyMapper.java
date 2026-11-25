package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.skill.SkillVersionDependencyBean;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 版本依赖。确认导入前不写这张表。 */
@Mapper
public interface SkillVersionDependencyMapper extends BaseMapper<SkillVersionDependencyBean> {
    @Select("""
            SELECT id, version_id, source_key, capability_key, required_flag, target_kind,
                   target_provider_key, target_tool_key, mapping_state, reason, target_snapshot_json,
                   gmt_create, gmt_modified
            FROM ai_skill_version_dependency
            WHERE version_id = #{versionId}
            ORDER BY source_key
            """)
    List<SkillVersionDependencyBean> listByVersion(@Param("versionId") long versionId);

    @Insert("""
            INSERT INTO ai_skill_version_dependency (
                version_id, source_key, capability_key, required_flag, target_kind, target_provider_key,
                target_tool_key, mapping_state, reason, target_snapshot_json, gmt_create, gmt_modified)
            VALUES (
                #{versionId}, #{sourceKey}, #{capabilityKey}, #{requiredFlag}, #{targetKind},
                #{targetProviderKey}, #{targetToolKey}, #{mappingState}, #{reason}, #{targetSnapshotJson},
                #{gmtCreate}, #{gmtModified})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertDependency(SkillVersionDependencyBean row);

    @Insert("""
            <script>
            INSERT INTO ai_skill_version_dependency (
                version_id, source_key, capability_key, required_flag, target_kind, target_provider_key,
                target_tool_key, mapping_state, reason, target_snapshot_json, gmt_create, gmt_modified)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.versionId}, #{row.sourceKey}, #{row.capabilityKey}, #{row.requiredFlag}, #{row.targetKind},
                 #{row.targetProviderKey}, #{row.targetToolKey}, #{row.mappingState}, #{row.reason},
                 #{row.targetSnapshotJson}, #{row.gmtCreate}, #{row.gmtModified})
            </foreach>
            </script>
            """)
    int insertDependencies(@Param("rows") List<SkillVersionDependencyBean> rows);

    @Delete("DELETE FROM ai_skill_version_dependency WHERE version_id = #{versionId}")
    int deleteByVersion(@Param("versionId") long versionId);
}
