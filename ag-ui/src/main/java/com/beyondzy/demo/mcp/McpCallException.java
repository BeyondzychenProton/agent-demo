package com.beyondzy.demo.mcp;

/** MCP 传输或协议失败。不携带手机号、签名或 Secret。 */
public final class McpCallException extends RuntimeException {

    private final String code;

    public McpCallException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? "TRANSPORT_FAILURE" : code;
    }

    public String code() {
        return code;
    }
}
