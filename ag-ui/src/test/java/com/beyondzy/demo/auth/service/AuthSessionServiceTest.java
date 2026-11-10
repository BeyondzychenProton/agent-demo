package com.beyondzy.demo.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.beyondzy.demo.auth.cookie.TokenHasher;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import com.beyondzy.demo.mapper.auth.AuthSessionMapper;
import com.beyondzy.demo.support.DbTimes;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class AuthSessionServiceTest {
    private AuthSessionMapper mapper;
    private AuthSessionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AuthSessionMapper.class);
        service = new AuthSessionService(mapper, Duration.ofDays(7), Duration.ofDays(1));
    }

    @Test
    void createPersistsHashedToken() {
        when(mapper.insert(any(AuthSessionBean.class))).thenAnswer(invocation -> {
            AuthSessionBean row = invocation.getArgument(0);
            row.setId(9L);
            return 1;
        });
        var created = service.create(1L);
        assertNotNull(created.rawToken());
        assertEquals(TokenHasher.sha256(created.rawToken()), created.row().getTokenHash());
        assertTrue(created.row().getExpiresAt().isAfter(DbTimes.now()));
    }

    @Test
    void expiredSessionIsRejected() {
        AuthSessionBean row = new AuthSessionBean();
        row.setId(1L);
        row.setUserId(1L);
        row.setTokenHash(TokenHasher.sha256("raw"));
        row.setExpiresAt(DbTimes.now().minusSeconds(1));
        row.setRenewedAt(DbTimes.now().minusSeconds(1));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);
        assertNull(service.findValidByRawToken("raw"));
    }

    @Test
    void renewOnlyAfterIntervalAndDoesNotExtendExpiry() {
        LocalDateTime expires = DbTimes.now().plus(Duration.ofDays(5));
        LocalDateTime renewedAt = DbTimes.now().minus(Duration.ofDays(2));
        AuthSessionBean row = new AuthSessionBean();
        row.setId(3L);
        row.setUserId(1L);
        row.setTokenHash(TokenHasher.sha256("raw"));
        row.setExpiresAt(expires);
        row.setRenewedAt(renewedAt);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);
        when(mapper.updateRenewedAt(eq(3L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        var validated = service.validateAndMaybeRenew("raw");
        assertNotNull(validated);
        assertTrue(validated.renewed());
        assertEquals(expires, validated.row().getExpiresAt());
        verify(mapper).updateRenewedAt(eq(3L), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void skipRenewWithinInterval() {
        LocalDateTime now = DbTimes.now();
        AuthSessionBean row = new AuthSessionBean();
        row.setId(4L);
        row.setUserId(1L);
        row.setTokenHash(TokenHasher.sha256("raw"));
        row.setExpiresAt(now.plus(Duration.ofDays(6)));
        row.setRenewedAt(now.minus(Duration.ofHours(1)));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);

        var validated = service.validateAndMaybeRenew("raw");
        assertNotNull(validated);
        assertFalse(validated.renewed());
        verify(mapper, never()).updateRenewedAt(
                ArgumentMatchers.anyLong(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void remainingMaxAgeUsesAbsoluteExpiry() {
        AuthSessionBean row = new AuthSessionBean();
        row.setExpiresAt(DbTimes.now().plusSeconds(90));
        long remaining = service.remainingMaxAgeSeconds(row);
        // 允许跨秒取整抖动，仍应按绝对过期计算秒级剩余。
        assertTrue(remaining >= 89 && remaining <= 90, "remaining=" + remaining);
    }

    @Test
    void validateReadOnlyNeverTouchesRenewedOrModifiedTimestamps() {
        LocalDateTime expires = DbTimes.now().plus(Duration.ofDays(5));
        LocalDateTime renewedAt = DbTimes.now().minus(Duration.ofDays(2));
        LocalDateTime gmtModified = renewedAt.minusHours(3);
        AuthSessionBean row = new AuthSessionBean();
        row.setId(5L);
        row.setUserId(1L);
        row.setTokenHash(TokenHasher.sha256("raw-ro"));
        row.setExpiresAt(expires);
        row.setRenewedAt(renewedAt);
        row.setGmtModified(gmtModified);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);

        var validated = service.validateReadOnly("raw-ro");

        assertNotNull(validated);
        assertFalse(validated.renewed());
        assertEquals(expires, validated.row().getExpiresAt());
        assertEquals(renewedAt, validated.row().getRenewedAt());
        assertEquals(gmtModified, validated.row().getGmtModified());
        verify(mapper, never()).updateRenewedAt(
                ArgumentMatchers.anyLong(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(mapper, never()).update(any(AuthSessionBean.class), any(Wrapper.class));
        verify(mapper, never()).updateById(any(AuthSessionBean.class));
    }
}
