package com.beyondzy.demo.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 治理 Identity Connector 回调的会话内省入口。
 *
 * <p>路径：{@code POST /api/internal/identity/session/introspect}。<br>
 * 机器密钥：{@code X-Identity-Connector-Key} 或治理 {@code X-Client-Authorization: Basic ...}。<br>
 * 用户会话：{@code Authorization: Bearer &lt;rawSessionToken&gt;}（与登录会话同源）。
 */
@RestController
@RequestMapping(value = "/api/internal/identity/session", produces = MediaType.APPLICATION_JSON_VALUE)
public class SessionIntrospectController {
    private final SessionIntrospectionService introspection;
    private final String sharedKey;

    public SessionIntrospectController(
            SessionIntrospectionService introspection,
            @Value("${chat.identity-connector.shared-key:}") String sharedKey) {
        this.introspection = introspection;
        this.sharedKey = sharedKey;
    }

    @PostMapping("/introspect")
    public Mono<Map<String, Object>> introspect(ServerWebExchange exchange) {
        String keyHeader = exchange.getRequest().getHeaders().getFirst(IdentityConnectorKeySupport.HEADER_SHARED_KEY);
        String clientAuth =
                exchange.getRequest().getHeaders().getFirst(IdentityConnectorKeySupport.HEADER_CLIENT_AUTHORIZATION);
        if (!IdentityConnectorKeySupport.matches(sharedKey, keyHeader, clientAuth)) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "机器身份校验失败"));
        }
        String rawToken = bearerToken(exchange);
        return Mono.fromCallable(() -> toBody(introspection.introspect(rawToken)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> toBody(SessionIntrospectionService.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", result.valid());
        body.put("userId", result.userId());
        body.put("phone", result.phone());
        body.put("name", result.name());
        if (result.authSessionId() != null) body.put("authSessionId", result.authSessionId());
        if (result.expiresAt() != null) body.put("expiresAt", result.expiresAt());
        return body;
    }

    private static String bearerToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
