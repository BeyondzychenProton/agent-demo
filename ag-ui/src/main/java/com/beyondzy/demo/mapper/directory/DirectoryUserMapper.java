package com.beyondzy.demo.mapper.directory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.directory.DirectoryUserBean;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 用户通讯录 Mapper。 */
@Mapper
public interface DirectoryUserMapper extends BaseMapper<DirectoryUserBean> {
    @Select("""
            SELECT source_id, department_id, status, is_deleted
            FROM app_directory_user
            WHERE source_id = #{id}
            """)
    DirectoryUserBean findSkillViewer(@Param("id") long id);

    /** 原子写入更新版本的数据，最后更新版本列，让所有字段都与旧版本比较。 */
    @Insert("""
            INSERT INTO app_directory_user
              (source_id, source_version, department_id, account, name, email, status, is_deleted)
            VALUES (#{row.sourceId}, #{row.sourceVersion}, #{row.departmentId}, #{row.account}, #{row.name}, #{row.email}, #{row.status}, #{row.isDeleted}) AS incoming
            ON DUPLICATE KEY UPDATE
              department_id = IF(incoming.source_version > app_directory_user.source_version, incoming.department_id, app_directory_user.department_id),
              account = IF(incoming.source_version > app_directory_user.source_version, incoming.account, app_directory_user.account),
              name = IF(incoming.source_version > app_directory_user.source_version, incoming.name, app_directory_user.name),
              email = IF(incoming.source_version > app_directory_user.source_version, incoming.email, app_directory_user.email),
              status = IF(incoming.source_version > app_directory_user.source_version, incoming.status, app_directory_user.status),
              is_deleted = IF(incoming.source_version > app_directory_user.source_version, incoming.is_deleted, app_directory_user.is_deleted),
              source_version = GREATEST(incoming.source_version, app_directory_user.source_version)
            """)
    int upsert(@Param("row") DirectoryUserBean row);
}
