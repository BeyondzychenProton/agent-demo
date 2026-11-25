package com.beyondzy.demo.mapper.mcp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.mcp.McpServiceBean;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface McpServiceMapper extends BaseMapper<McpServiceBean> {
    @Insert("""
        INSERT INTO ai_mcp_service
          (provider_key, display_name, gmt_create, gmt_modified)
        VALUES (#{key}, #{name}, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE provider_key = VALUES(provider_key)
        """)
    void ensure(@Param("key") String key, @Param("name") String name);

    @Select("SELECT * FROM ai_mcp_service WHERE id=#{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    McpServiceBean lock(@Param("id") long id);

    @Select("""
        SELECT MAX(gmt_create) FROM ai_mcp_operation
        WHERE service_id=#{id} AND origin='ADMIN'
          AND action IN ('METADATA_UPDATED','SERVICE_STATUS_CHANGED')
        """)
    @Options(useCache = false)
    java.time.LocalDateTime lastManualChange(@Param("id") long id);

    @Select("""
        <script>
        SELECT service_id AS serviceId, MAX(gmt_create) AS changedAt
        FROM ai_mcp_operation
        WHERE origin='ADMIN'
          AND action IN ('METADATA_UPDATED','SERVICE_STATUS_CHANGED')
          AND service_id IN
          <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY service_id
        </script>
        """)
    @Options(useCache = false)
    List<ManualChange> lastManualChanges(@Param("ids") Collection<Long> ids);

    @Select("""
        SELECT s.enabled AS serviceEnabled, t.enabled AS toolEnabled, t.present AS present
        FROM ai_mcp_service s JOIN ai_mcp_tool t ON t.service_id=s.id
        WHERE s.provider_key=#{provider} AND t.tool_key=#{tool}
        """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    InvocationState invocationState(@Param("provider") String provider, @Param("tool") String tool);

    final class ManualChange {
        public Long serviceId;
        public LocalDateTime changedAt;
    }

    final class InvocationState {
        public Integer serviceEnabled;
        public Integer toolEnabled;
        public Integer present;
    }
}
