package com.beyondzy.demo.service.auth;

import com.beyondzy.demo.domain.auth.AuthSessionBean;

/** 登录会话的创建、校验、续期与注销。 */
public interface AuthSessionService {
    record CreatedSession(String rawToken, AuthSessionBean row) {}

    record ValidatedSession(AuthSessionBean row, boolean renewed) {}

    CreatedSession create(long userId);

    AuthSessionBean findValidByRawToken(String rawToken);

    /**
     * 在绝对过期前，若距上次续期已超过间隔则刷新 renewed_at。不延长 expires_at。
     */
    ValidatedSession validateAndMaybeRenew(String rawToken);

    void deleteById(long id);

    void deleteByRawToken(String rawToken);

    long remainingMaxAgeSeconds(AuthSessionBean row);
}
