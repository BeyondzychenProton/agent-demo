package com.beyondzy.demo.exception.mcp;

import com.beyondzy.demo.enums.mcp.McpCallCode;

/** MCP 传输或协议失败。不携带手机号、签名或 Secret。 */
public final class McpCallException extends RuntimeException {

    private final String code;

    public McpCallException(McpCallCode code, String message) {
        this(code.name(), message);
    }

    public McpCallException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? McpCallCode.TRANSPORT_FAILURE.name() : code;
    }

    public String code() {
        return code;
    }
}
