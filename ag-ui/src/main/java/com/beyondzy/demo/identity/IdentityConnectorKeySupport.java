package com.beyondzy.demo.identity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 校验治理连接器机器身份。
 *
 * <p>本工程推荐头 {@code X-Identity-Connector-Key}；治理侧 {@code credential_ref} 当前会发
 * {@code X-Client-Authorization: Basic base64(appKey:secret)}，两者都接受，只要 secret 匹配配置值。
 */
public final class IdentityConnectorKeySupport {
    public static final String HEADER_SHARED_KEY = "X-Identity-Connector-Key";
    public static final String HEADER_CLIENT_AUTHORIZATION = "X-Client-Authorization";

    private IdentityConnectorKeySupport() {}

    public static boolean matches(String configuredSecret, String sharedKeyHeader, String clientAuthorization) {
        if (configuredSecret == null || configuredSecret.isBlank()) return false;
        if (sharedKeyHeader != null && !sharedKeyHeader.isBlank()) {
            return constantTimeEquals(configuredSecret, sharedKeyHeader.trim());
        }
        if (clientAuthorization == null || clientAuthorization.isBlank()) return false;
        String value = clientAuthorization.trim();
        if (!value.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(value.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            String secret = colon < 0 ? decoded : decoded.substring(colon + 1);
            return constantTimeEquals(configuredSecret, secret);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) return false;
        int diff = 0;
        for (int i = 0; i < left.length; i++) diff |= left[i] ^ right[i];
        return diff == 0;
    }
}
