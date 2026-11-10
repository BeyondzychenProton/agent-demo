package com.beyondzy.demo.auth.support;

import com.beyondzy.demo.auth.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** 从 Reactive SecurityContext 读取当前登录用户。 */
public final class CurrentUser {
    private CurrentUser() {}

    public static Mono<AuthPrincipal> require() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(AuthPrincipal.class::isInstance)
                .cast(AuthPrincipal.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录")));
    }

    public static Mono<Long> requireUserId() {
        return require().map(AuthPrincipal::userId);
    }

    /** 当前认证里的不透明会话 rawToken（与 Cookie/Bearer 同源）。 */
    public static Mono<String> requireRawToken() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getCredentials)
                .filter(credentials -> credentials != null)
                .map(String::valueOf)
                .filter(token -> !token.isBlank())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少会话凭证")));
    }
}
