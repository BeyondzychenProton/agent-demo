package com.beyondzy.demo.auth.security;

import com.beyondzy.demo.auth.cookie.AuthCookieSupport;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 优先 Bearer，否则解密 Cookie，得到待校验的原始 token。 */
@Component
public class SessionTokenAuthenticationConverter implements ServerAuthenticationConverter {
    private final AuthCookieSupport cookies;

    public SessionTokenAuthenticationConverter(AuthCookieSupport cookies) {
        this.cookies = cookies;
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String bearer = bearerToken(exchange);
        String raw = bearer != null ? bearer : cookies.readRawToken(exchange);
        if (raw == null || raw.isBlank()) return Mono.empty();
        return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated(raw, raw));
    }

    private static String bearerToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
