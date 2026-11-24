package com.beyondzy.demo.mapper.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.skill.SkillVersionFileBean;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 版本文件。详情不读取字节，下载才读 content。 */
@Mapper
public interface SkillVersionFileMapper extends BaseMapper<SkillVersionFileBean> {
    @Select("""
            SELECT id, version_id, resource_path, file_kind, content_sha256, byte_size, gmt_create, gmt_modified
            FROM ai_skill_version_file
            WHERE version_id = #{versionId}
            ORDER BY resource_path
            """)
    List<SkillVersionFileBean> listMeta(@Param("versionId") long versionId);

    @Select("""
            SELECT id, version_id, resource_path, file_kind, content, content_sha256, byte_size
            FROM ai_skill_version_file
            WHERE version_id = #{versionId}
            ORDER BY resource_path
            """)
    List<SkillVersionFileBean> listContent(@Param("versionId") long versionId);

    @Select("""
            SELECT id, version_id, resource_path, file_kind, content, content_sha256, byte_size
            FROM ai_skill_version_file
            WHERE id = #{id}
            """)
    SkillVersionFileBean find(@Param("id") long id);

    @Insert("""
            INSERT INTO ai_skill_version_file (
                version_id, resource_path, file_kind, content, content_sha256, byte_size, gmt_create, gmt_modified)
            VALUES (
                #{versionId}, #{resourcePath}, #{fileKind}, #{content}, #{contentSha256}, #{byteSize},
                #{gmtCreate}, #{gmtModified})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertFile(SkillVersionFileBean row);

    @Delete("DELETE FROM ai_skill_version_file WHERE version_id = #{versionId}")
    int deleteByVersion(@Param("versionId") long versionId);
}
