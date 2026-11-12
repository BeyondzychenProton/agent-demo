package com.beyondzy.demo.auth.security;

import com.beyondzy.demo.auth.cookie.AuthCookieSupport;
import com.beyondzy.demo.auth.service.AuthSessionService;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.user.UserService;
import java.util.List;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 校验不透明 token，并在需要时滑动续期。 */
@Component
public class SessionTokenAuthenticationManager implements ReactiveAuthenticationManager {
    private final AuthSessionService sessions;
    private final UserService users;
    private final AuthCookieSupport cookies;

    public SessionTokenAuthenticationManager(
            AuthSessionService sessions, UserService users, AuthCookieSupport cookies) {
        this.sessions = sessions;
        this.users = users;
        this.cookies = cookies;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        Object credentials = authentication.getCredentials();
        if (credentials == null) return Mono.error(new BadCredentialsException("缺少凭证"));
        String rawToken = String.valueOf(credentials);
        return Mono.<Authentication>fromCallable(() -> {
                    AuthOutcome outcome = validate(rawToken);
                    if (outcome == null) throw new BadCredentialsException("会话无效或已过期");
                    return outcome.auth();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 鉴权结果；details 中携带以便成功处理器续写 Cookie。 */
    public record AuthOutcome(
            Authentication auth,
            AuthSessionService.ValidatedSession validated,
            String rawToken) {}

    public AuthOutcome validate(String rawToken) {
        return toOutcome(rawToken, sessions.validateAndMaybeRenew(rawToken));
    }

    private AuthOutcome toOutcome(String rawToken, AuthSessionService.ValidatedSession validated) {
        if (validated == null) return null;
        UserBean user = users.findById(validated.row().getUserId());
        if (user == null) return null;
        AuthPrincipal principal = new AuthPrincipal(
                user.getId(), validated.row().getId(), user.getPhone(), user.getName());
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, rawToken, List.of());
        AuthOutcome outcome = new AuthOutcome(auth, validated, rawToken);
        auth.setDetails(outcome);
        return outcome;
    }

    public void renewCookieIfNeeded(
            org.springframework.web.server.ServerWebExchange exchange, AuthOutcome outcome) {
        if (outcome == null || !outcome.validated().renewed()) return;
        cookies.write(exchange.getResponse(), outcome.rawToken(),
                sessions.remainingMaxAgeSeconds(outcome.validated().row()));
    }
}
