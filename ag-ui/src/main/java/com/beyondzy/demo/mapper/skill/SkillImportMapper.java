package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.beyondzy.demo.domain.skill.SkillImportBean;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 导入批次。更新都带 revision 条件，避免后写覆盖先写。 */
@Mapper
public interface SkillImportMapper extends BaseMapper<SkillImportBean> {
    String COLUMNS = """
            id, request_key, created_by, state, filename, package_sha256, target_name, entry_policy,
            content_policy, check_report_json, checker_version, confirmed_by, confirmed_at,
            result_version_id, result_json, error_code, expires_at, revision, gmt_create, gmt_modified,
            CASE WHEN package_body IS NULL THEN 0 ELSE 1 END AS source_available
            """;

    @Select("SELECT " + COLUMNS + " FROM ai_skill_import WHERE created_by = #{actor} AND request_key = #{key}")
    SkillImportBean findByRequest(@Param("actor") long actor, @Param("key") String key);

    @Select("SELECT " + COLUMNS + " FROM ai_skill_import WHERE id = #{id}")
    SkillImportBean find(@Param("id") long id);

    @Select("SELECT " + COLUMNS + ", package_body FROM ai_skill_import WHERE id = #{id} FOR UPDATE")
    SkillImportBean lock(@Param("id") long id);

    /** 返回整行是为了让字节列走属性映射。直接返回 byte[] 会被当成单字节。 */
    @Select("SELECT package_body FROM ai_skill_import WHERE id = #{id} AND created_by = #{actor}")
    SkillImportBean findBody(@Param("id") long id, @Param("actor") long actor);

    @Select("SELECT " + COLUMNS + " FROM ai_skill_import WHERE created_by = #{actor} "
            + "AND (#{state} IS NULL OR state = #{state}) ORDER BY id DESC")
    IPage<SkillImportBean> page(IPage<SkillImportBean> page, @Param("actor") long actor, @Param("state") String state);

    @Select("""
            SELECT COUNT(*) FROM ai_skill_import
            WHERE created_by = #{actor}
              AND (#{state} IS NULL OR state = #{state})
            """)
    long count(@Param("actor") long actor, @Param("state") String state);

    @Insert("""
            INSERT INTO ai_skill_import (
                request_key, created_by, state, filename, package_sha256, package_body, target_name,
                entry_policy, content_policy, check_report_json, checker_version, error_code, expires_at,
                revision, gmt_create, gmt_modified)
            VALUES (
                #{requestKey}, #{createdBy}, #{state}, #{filename}, #{packageSha256}, #{packageBody},
                #{targetName}, #{entryPolicy}, #{contentPolicy}, #{checkReportJson}, #{checkerVersion},
                #{errorCode}, #{expiresAt}, #{revision}, #{gmtCreate}, #{gmtModified})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertImport(SkillImportBean row);

    @Update("""
            UPDATE ai_skill_import
            SET state = #{state},
                target_name = #{targetName},
                entry_policy = #{entryPolicy},
                content_policy = #{contentPolicy},
                check_report_json = #{checkReportJson},
                checker_version = #{checkerVersion},
                error_code = #{errorCode},
                revision = revision + 1,
                gmt_modified = #{gmtModified}
            WHERE id = #{id} AND revision = #{revision}
            """)
    int updateReport(SkillImportBean row);

    @Update("""
            UPDATE ai_skill_import
            SET state = 'CONFIRMED',
                confirmed_by = #{confirmedBy},
                confirmed_at = #{confirmedAt},
                result_version_id = #{resultVersionId},
                result_json = #{resultJson},
                check_report_json = #{checkReportJson},
                error_code = NULL,
                revision = revision + 1,
                gmt_modified = #{gmtModified}
            WHERE id = #{id} AND revision = #{revision} AND state = 'CHECKED'
            """)
    int confirm(SkillImportBean row);

    @Update("""
            UPDATE ai_skill_import
            SET state = 'CANCELLED',
                revision = revision + 1,
                gmt_modified = #{gmtModified}
            WHERE id = #{id} AND revision = #{revision}
              AND state IN ('UPLOADED', 'CHECKED', 'FAILED')
            """)
    int cancel(SkillImportBean row);

    @Update("""
            UPDATE ai_skill_import
            SET state = 'EXPIRED', revision = revision + 1, gmt_modified = #{now}
            WHERE id = #{id} AND revision = #{revision}
              AND state IN ('UPLOADED', 'CHECKED', 'FAILED')
            """)
    int expire(@Param("id") long id, @Param("revision") long revision, @Param("now") LocalDateTime now);

    @Update("""
            <script>
            UPDATE ai_skill_import
            SET state = 'EXPIRED', revision = revision + 1, gmt_modified = #{now}
            WHERE state IN ('UPLOADED', 'CHECKED', 'FAILED')
              AND (id, revision) IN
            <foreach collection="rows" item="row" open="(" separator="," close=")">
                (#{row.id}, #{row.revision})
            </foreach>
            </script>
            """)
    int expireAll(@Param("rows") List<Due> rows, @Param("now") LocalDateTime now);

    record Due(long id, long revision) {}
}
