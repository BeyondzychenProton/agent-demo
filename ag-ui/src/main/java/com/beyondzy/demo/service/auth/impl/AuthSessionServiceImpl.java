package com.beyondzy.demo.service.auth.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import com.beyondzy.demo.handler.admin.TokenHasher;
import com.beyondzy.demo.mapper.auth.AuthSessionMapper;
import com.beyondzy.demo.service.auth.AuthSessionService;
import com.beyondzy.demo.service.auth.AuthSessionService.CreatedSession;
import com.beyondzy.demo.service.auth.AuthSessionService.ValidatedSession;
import com.beyondzy.demo.support.DbTimes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 登录会话的创建、校验、续期与注销。 */
@Service
public class AuthSessionServiceImpl implements AuthSessionService {
    private final AuthSessionMapper mapper;
    private final Duration absoluteTtl;
    private final Duration renewInterval;

    public AuthSessionServiceImpl(
            AuthSessionMapper mapper,
            @Value("${chat.auth.absolute-ttl:7d}") Duration absoluteTtl,
            @Value("${chat.auth.renew-interval:1d}") Duration renewInterval) {
        this.mapper = mapper;
        this.absoluteTtl = absoluteTtl;
        this.renewInterval = renewInterval;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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

    @Override
    public AuthSessionBean findValidByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        AuthSessionBean row = mapper.selectOne(Wrappers.<AuthSessionBean>lambdaQuery()
                .eq(AuthSessionBean::getTokenHash, TokenHasher.sha256(rawToken)));
        if (row == null) return null;
        if (row.getExpiresAt() == null || !row.getExpiresAt().isAfter(DbTimes.now())) return null;
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(long id) {
        mapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        mapper.delete(Wrappers.<AuthSessionBean>lambdaQuery()
                .eq(AuthSessionBean::getTokenHash, TokenHasher.sha256(rawToken)));
    }

    @Override
    public long remainingMaxAgeSeconds(AuthSessionBean row) {
        long remainingMs = Math.max(0, DbTimes.toEpochMilli(row.getExpiresAt()) - System.currentTimeMillis());
        return Math.max(1, remainingMs / 1000);
    }
}
