package com.beyondzy.demo.mapper.directory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.directory.DirectoryDepartmentBean;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 部门通讯录 Mapper。 */
@Mapper
public interface DirectoryDepartmentMapper extends BaseMapper<DirectoryDepartmentBean> {
    /** 原子写入更新版本的数据，最后更新版本列，让所有字段都与旧版本比较。 */
    @Insert("""
            INSERT INTO app_directory_department
              (source_id, source_version, parent_id, name, sort_order, status, is_deleted)
            VALUES (#{row.sourceId}, #{row.sourceVersion}, #{row.parentId}, #{row.name}, #{row.sortOrder}, #{row.status}, #{row.isDeleted}) AS incoming
            ON DUPLICATE KEY UPDATE
              parent_id = IF(incoming.source_version > app_directory_department.source_version, incoming.parent_id, app_directory_department.parent_id),
              name = IF(incoming.source_version > app_directory_department.source_version, incoming.name, app_directory_department.name),
              sort_order = IF(incoming.source_version > app_directory_department.source_version, incoming.sort_order, app_directory_department.sort_order),
              status = IF(incoming.source_version > app_directory_department.source_version, incoming.status, app_directory_department.status),
              is_deleted = IF(incoming.source_version > app_directory_department.source_version, incoming.is_deleted, app_directory_department.is_deleted),
              source_version = GREATEST(incoming.source_version, app_directory_department.source_version)
            """)
    int upsert(@Param("row") DirectoryDepartmentBean row);
}
