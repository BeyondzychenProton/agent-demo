package com.beyondzy.demo.mcp;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 满天星 MCP 每次请求的 HMAC-SHA256 签名。载荷是毫秒时间戳、一个 LF、登录手机号。 */
public final class McpSignature {

    private McpSignature() {}

    public static String sign(String secret, String timestampMillis, String userPhone) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("MCP Secret 不能为空");
        if (timestampMillis == null || timestampMillis.isBlank()) {
            throw new IllegalArgumentException("MCP 时间戳不能为空");
        }
        if (userPhone == null || userPhone.isBlank()) throw new IllegalArgumentException("MCP 登录手机号不能为空");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((timestampMillis + "\n" + userPhone).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception error) {
            throw new IllegalStateException("MCP 签名失败");
        }
    }
}
