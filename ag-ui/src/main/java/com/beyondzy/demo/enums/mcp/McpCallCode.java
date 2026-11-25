package com.beyondzy.demo.enums.mcp;
import io.swagger.v3.oas.annotations.media.Schema;


/** MCP 调用失败码。常量名就是对外的 code，各调用点的失败文案仍写在调用点。 */
@Schema(description = "MCP 调用失败码。常量名就是对外的 code，各调用点的失败文案仍写在调用点。")
public enum McpCallCode {
    @Schema(description = "未能确认调用者身份")
    UNAUTHENTICATED("未能确认调用者身份"),
    @Schema(description = "MCP 服务未就绪")
    SERVER_UNAVAILABLE("MCP 服务未就绪"),
    @Schema(description = "传输失败或调用被中断")
    TRANSPORT_FAILURE("传输失败或调用被中断"),
    @Schema(description = "响应不符合 MCP 协议")
    PROTOCOL("响应不符合 MCP 协议"),
    @Schema(description = "调用参数不合法")
    INVALID_ARGUMENT("调用参数不合法"),
    @Schema(description = "工具目录无效")
    INVALID_CATALOG("工具目录无效"),
    @Schema(description = "工具返回错误")
    TOOL_ERROR("工具返回错误"),
    @Schema(description = "工具目录没有读完")
    DISCOVERY_INCOMPLETE("工具目录没有读完"),
    @Schema(description = "工具发现超过时限")
    DISCOVERY_TIMEOUT("工具发现超过时限"),
    @Schema(description = "暂时读不到 MCP 管理状态")
    MCP_STATE_UNAVAILABLE("暂时读不到 MCP 管理状态"),
    @Schema(description = "工具不在当前有效目录中")
    MCP_TOOL_UNAVAILABLE("工具不在当前有效目录中"),
    @Schema(description = "MCP 服务已停用")
    MCP_SERVICE_DISABLED("MCP 服务已停用"),
    @Schema(description = "工具已关闭")
    MCP_TOOL_DISABLED("工具已关闭"),
    @Schema(description = "当前进程尚未加载该工具")
    MCP_TOOL_NOT_LOADED("当前进程尚未加载该工具");

    private final String description;

    McpCallCode(String description) {
        this.description = description;
    }

    /** 稳定的中文定义，不是某一次调用的失败文案。 */
    public String description() {
        return description;
    }

    /** 状态码不固定，不能做成枚举常量。 */
    public static String http(int status) {
        return "HTTP_" + status;
    }
}
