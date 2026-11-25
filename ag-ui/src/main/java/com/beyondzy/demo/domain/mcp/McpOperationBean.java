package com.beyondzy.demo.domain.mcp;

import lombok.Data;
import lombok.EqualsAndHashCode;

import com.baomidou.mybatisplus.annotation.*;
import com.beyondzy.demo.domain.BaseAuditBean;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** MCP 服务的管理操作记录，只追加，不记录每次工具调用。 */
@Schema(description = "MCP 服务的管理操作记录，只追加，不记录每次工具调用。")
@TableName("ai_mcp_operation")
@Data
@EqualsAndHashCode(callSuper = true)
public class McpOperationBean extends BaseAuditBean {
    @Schema(description = "操作记录主键")
    @TableId(type = IdType.AUTO)
    private Long id;
    @Schema(description = "所属 ai_mcp_service.id")
    private Long serviceId;
    @Schema(description = "工具级操作对应的 ai_mcp_tool.id，服务级操作为空")
    private Long toolId;
    @Schema(description = "管理动作：METADATA_UPDATED 保存信息、SERVICE_STATUS_CHANGED 服务开关、TOOL_STATUS_CHANGED 工具开关、CONNECTION_VERIFIED 重新验证、CATALOG_SYNCED 启动同步目录")
    private String action;
    @Schema(description = "操作人 app_user.id，系统操作时为空")
    private Long actorId;
    @Schema(description = "操作当时写入的展示名")
    private String actorName;
    @Schema(description = "操作来源：ADMIN 人工，SYSTEM 系统")
    private String origin;
    @Schema(description = "结果：SUCCESS 成功、FAILED 失败、SUPERSEDED 已被更新的检测取代")
    private String result;
    @Schema(description = "本次操作写入的修改前后值、工具键、目录增减计数或错误码")
    private String detailJson;
}
