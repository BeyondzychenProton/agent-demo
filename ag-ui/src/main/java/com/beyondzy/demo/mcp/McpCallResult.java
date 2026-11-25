package com.beyondzy.demo.mcp;

import com.beyondzy.demo.enums.mcp.McpCallCode;

/** 一次 tools/call 的归类结果。业务 JSON 原样放在 body，失败不改写成成功。 */
public record McpCallResult(boolean success, String code, String message, Object body) {

    public static McpCallResult success(Object body) {
        return new McpCallResult(true, null, null, body);
    }

    public static McpCallResult failure(McpCallCode code, String message, Object body) {
        return failure(code.name(), message, body);
    }

    public static McpCallResult failure(String code, String message, Object body) {
        return new McpCallResult(false, code, message, body);
    }
}
