package com.beyondzy.demo.domain.mcp;

import lombok.Data;
import lombok.EqualsAndHashCode;

import com.baomidou.mybatisplus.annotation.*;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 某个 MCP 服务下远端工具的发现快照和独立开关。 */
@Schema(description = "某个 MCP 服务下远端工具的发现快照和独立开关。")
@TableName("ai_mcp_tool")
@Data
@EqualsAndHashCode(callSuper = true)
public class McpToolBean extends BaseAuditBean {
    @Schema(description = "工具主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "所属 ai_mcp_service.id")
    private Long serviceId;
    @Schema(description = "远端原始工具名，同一服务内唯一且区分大小写")
    private String toolKey;
    @Schema(description = "最近一次完整成功发现写入的工具说明")
    private String description;
    @Schema(description = "最近一次完整成功发现写入的输入结构")
    private String inputSchemaJson;
    @Schema(description = "本地读写策略：1 写操作，0 读操作")
    private Integer writeFlag;
    @Schema(description = "工具名、说明、输入结构和读写标记规范化后的 SHA-256 十六进制摘要")
    private String definitionHash;
    @Schema(description = "工具独立开关：1 开放，0 关闭")
    private Integer enabled;
    @Schema(description = "最近一次完整成功目录中是否仍存在：1 存在，0 已消失")
    private Integer present;
    @Schema(description = "人工修改工具开关时的乐观锁版本，发现刷新不更新")
    private Long controlVersion;
    @Schema(description = "首次发现并写入该工具的时间")
    private LocalDateTime firstDiscoveredAt;
    @Schema(description = "最近一次成功目录仍包含该工具的时间")
    private LocalDateTime lastSeenAt;
    @Schema(description = "最近一次人工修改工具开关的 app_user.id")
    private Long updatedBy;
}
