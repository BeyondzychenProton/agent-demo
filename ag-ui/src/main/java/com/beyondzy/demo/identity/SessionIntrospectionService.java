package com.beyondzy.demo.identity;

import com.beyondzy.demo.auth.security.AuthPrincipal;
import com.beyondzy.demo.auth.security.SessionTokenAuthenticationManager;
import com.beyondzy.demo.support.DbTimes;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 机器用会话内省：复用 SessionTokenAuthenticationManager 的只读校验，phone 与 AuthPrincipal 同源。
 *
 * <p>只检查有效期与用户有效性，不触发滑动续期（不更新 renewed_at / gmt_modified / expires_at）。
 * 无效会话抛 401（治理按非 2xx 视为内省失败）。不返回 {@code valid:false} 的 200，避免误当成功。
 */
@Service
public class SessionIntrospectionService {
    private final SessionTokenAuthenticationManager authenticationManager;

    public SessionIntrospectionService(SessionTokenAuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    public record Result(
            boolean valid,
            String userId,
            String phone,
            String name,
            Long authSessionId,
            Long expiresAt) {}

    public Result introspect(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少会话凭证");
        }
        SessionTokenAuthenticationManager.AuthOutcome outcome =
                authenticationManager.validateReadOnly(rawToken.trim());
        if (outcome == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话无效或已过期");
        }
        Authentication auth = outcome.auth();
        if (!(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话主体无效");
        }
        Long expiresAt = outcome.validated().row().getExpiresAt() == null
                ? null
                : DbTimes.toEpochMilli(outcome.validated().row().getExpiresAt());
        return new Result(
                true,
                String.valueOf(principal.userId()),
                principal.phone(),
                principal.name(),
                principal.authSessionId(),
                expiresAt);
    }
}
