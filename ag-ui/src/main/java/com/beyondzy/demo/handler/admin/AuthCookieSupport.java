package com.beyondzy.demo.handler.admin;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/** 读写加密会话 Cookie。 */
@Component
public class AuthCookieSupport {
    private final String cookieName;
    private final boolean secure;
    private final CookieTokenCipher cipher;

    public AuthCookieSupport(
            CookieTokenCipher cipher,
            @Value("${chat.auth.cookie-name:AS_SESSION}") String cookieName,
            @Value("${chat.auth.cookie-secure:false}") boolean secure) {
        this.cipher = cipher;
        this.cookieName = cookieName;
        this.secure = secure;
    }

    public String cookieName() {
        return cookieName;
    }

    public void write(ServerHttpResponse response, String rawToken, long maxAgeSeconds) {
        String value = cipher.encrypt(rawToken);
        ResponseCookie cookie = ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .secure(secure)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
        response.addCookie(cookie);
    }

    public void clear(ServerHttpResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .secure(secure)
                .maxAge(Duration.ZERO)
                .build();
        response.addCookie(cookie);
    }

    public String readRawToken(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(cookieName);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) return null;
        try {
            return cipher.decrypt(cookie.getValue());
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
