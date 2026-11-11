package com.beyondzy.demo.mcp;

import java.util.Map;

/** 用指定登录手机号调用一个 MCP 工具。手机号只来自调用方，不从 arguments 推断。 */
public interface McpToolCaller {
    McpCallResult callTool(String toolName, Map<String, Object> arguments, String userPhone);
}
