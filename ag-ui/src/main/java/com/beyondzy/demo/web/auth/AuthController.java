package com.beyondzy.demo.web.auth;

import com.beyondzy.demo.handler.admin.AuthCookieSupport;
import com.beyondzy.demo.handler.admin.CurrentUser;
import com.beyondzy.demo.service.auth.AuthService;
import com.beyondzy.demo.service.auth.AuthSessionService;
import com.beyondzy.demo.web.auth.req.LoginRequest;
import com.beyondzy.demo.web.auth.res.AuthUserResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 登录 / 退出 / 当前用户。 */
@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {
    private final AuthService auth;
    private final AuthSessionService sessions;
    private final AuthCookieSupport cookies;
    @Value("${app.admin.phone:15738515168}")
    private String adminPhone;

    public AuthController(AuthService auth, AuthSessionService sessions, AuthCookieSupport cookies) {
        this.auth = auth;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<AuthUserResponse>> login(
            @RequestBody LoginRequest body, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> auth.login(body.phone(), body.password()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    cookies.write(exchange.getResponse(), result.session().rawToken(),
                            sessions.remainingMaxAgeSeconds(result.session().row()));
                    return ResponseEntity.ok(AuthUserResponse.of(
                            result.user(),
                            result.session().row().getExpiresAt(),
                            result.session().rawToken(), isAdmin(result.user().getPhone())));
                });
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        String raw = cookies.readRawToken(exchange);
        String bearer = bearer(exchange);
        String token = bearer != null ? bearer : raw;
        return Mono.fromRunnable(() -> {
                    auth.logout(token);
                    cookies.clear(exchange.getResponse());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/me")
    public Mono<AuthUserResponse> me() {
        return CurrentUser.require()
                .flatMap(principal -> Mono.fromCallable(() -> {
                    var user = auth.requireUser(principal.userId());
                    return AuthUserResponse.of(user, null, null, isAdmin(user.getPhone()));
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private boolean isAdmin(String phone) {
        return phone != null && !adminPhone.isBlank() && phone.equals(adminPhone.trim());
    }

    private static String bearer(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
