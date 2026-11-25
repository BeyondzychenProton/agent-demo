package com.beyondzy.demo.enums.mcp;
import io.swagger.v3.oas.annotations.media.Schema;


/** 已接入的 MCP 集成商。code 只用于运行时标识。 */
@Schema(description = "已接入的 MCP 集成商。code 只用于运行时标识。")
public enum McpProvider {
    MANTIANXING(Code.MANTIANXING);

    /** 运行时标识。路径注解要引用编译期常量，所以字面量只放在这里。 */
    public static final class Code {
        public static final String MANTIANXING = "mantianxing";

        private Code() {}
    }

    private final String code;

    McpProvider(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
