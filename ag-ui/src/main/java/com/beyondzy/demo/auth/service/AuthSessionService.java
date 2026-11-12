package com.beyondzy.demo.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.beyondzy.demo.auth.cookie.TokenHasher;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import com.beyondzy.demo.mapper.auth.AuthSessionMapper;
import com.beyondzy.demo.support.DbTimes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 登录会话的创建、校验、续期与注销。 */
@Service
public class AuthSessionService {
    private final AuthSessionMapper mapper;
    private final Duration absoluteTtl;
    private final Duration renewInterval;

    public AuthSessionService(
            AuthSessionMapper mapper,
            @Value("${chat.auth.absolute-ttl:7d}") Duration absoluteTtl,
            @Value("${chat.auth.renew-interval:1d}") Duration renewInterval) {
        this.mapper = mapper;
        this.absoluteTtl = absoluteTtl;
        this.renewInterval = renewInterval;
    }

    public record CreatedSession(String rawToken, AuthSessionBean row) {}

    public record ValidatedSession(AuthSessionBean row, boolean renewed) {}

    @Transactional
    public CreatedSession create(long userId) {
        LocalDateTime now = DbTimes.now();
        String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        AuthSessionBean row = new AuthSessionBean();
        row.setUserId(userId);
        row.setTokenHash(TokenHasher.sha256(rawToken));
        row.setExpiresAt(now.plus(absoluteTtl));
        row.setRenewedAt(now);
        mapper.insert(row);
        return new CreatedSession(rawToken, row);
    }

    public AuthSessionBean findValidByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        AuthSessionBean row = mapper.selectOne(Wrappers.<AuthSessionBean>lambdaQuery()
                .eq(AuthSessionBean::getTokenHash, TokenHasher.sha256(rawToken)));
        if (row == null) return null;
        if (row.getExpiresAt() == null || !row.getExpiresAt().isAfter(DbTimes.now())) return null;
        return row;
    }

    /** 在绝对过期前，若距上次续期已超过间隔则刷新 renewed_at。不延长 expires_at。 */
    @Transactional
    public ValidatedSession validateAndMaybeRenew(String rawToken) {
        AuthSessionBean row = findValidByRawToken(rawToken);
        if (row == null) return null;
        LocalDateTime now = DbTimes.now();
        boolean due = row.getRenewedAt() == null
                || Duration.between(row.getRenewedAt(), now).compareTo(renewInterval) >= 0;
        if (due) {
            mapper.updateRenewedAt(row.getId(), now, now);
            row.setRenewedAt(now);
            row.setGmtModified(now);
            return new ValidatedSession(row, true);
        }
        return new ValidatedSession(row, false);
    }

    @Transactional
    public void deleteById(long id) {
        mapper.deleteById(id);
    }

    @Transactional
    public void deleteByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        mapper.delete(Wrappers.<AuthSessionBean>lambdaQuery()
                .eq(AuthSessionBean::getTokenHash, TokenHasher.sha256(rawToken)));
    }

    public long remainingMaxAgeSeconds(AuthSessionBean row) {
        long remainingMs = Math.max(0, DbTimes.toEpochMilli(row.getExpiresAt()) - System.currentTimeMillis());
        return Math.max(1, remainingMs / 1000);
    }
}
