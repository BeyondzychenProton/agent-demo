package com.beyondzy.demo.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.beyondzy.demo.auth.cookie.TokenHasher;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import com.beyondzy.demo.mapper.auth.AuthSessionMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        assertTrue(created.row().getExpiresAt() > System.currentTimeMillis());
    }

    @Test
    void expiredSessionIsRejected() {
        AuthSessionBean row = new AuthSessionBean();
        row.setId(1L);
        row.setUserId(1L);
        row.setTokenHash(TokenHasher.sha256("raw"));
        row.setExpiresAt(System.currentTimeMillis() - 1000);
        row.setRenewedAt(System.currentTimeMillis() - 1000);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);
        assertNull(service.findValidByRawToken("raw"));
    }

    @Test
    void renewOnlyAfterIntervalAndDoesNotExtendExpiry() {
        long expires = System.currentTimeMillis() + Duration.ofDays(5).toMillis();
        long renewedAt = System.currentTimeMillis() - Duration.ofDays(2).toMillis();
        AuthSessionBean row = new AuthSessionBean();
        row.setId(3L);
        row.setUserId(1L);
        row.setTokenHash(TokenHasher.sha256("raw"));
        row.setExpiresAt(expires);
        row.setRenewedAt(renewedAt);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);
        when(mapper.updateRenewedAt(eq(3L), anyLong(), anyLong())).thenReturn(1);

        var validated = service.validateAndMaybeRenew("raw");
        assertNotNull(validated);
        assertTrue(validated.renewed());
        assertEquals(expires, validated.row().getExpiresAt());
        verify(mapper).updateRenewedAt(eq(3L), anyLong(), anyLong());
    }

    @Test
    void skipRenewWithinInterval() {
        long now = System.currentTimeMillis();
        AuthSessionBean row = new AuthSessionBean();
        row.setId(4L);
        row.setUserId(1L);
        row.setTokenHash(TokenHasher.sha256("raw"));
        row.setExpiresAt(now + Duration.ofDays(6).toMillis());
        row.setRenewedAt(now - Duration.ofHours(1).toMillis());
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);

        var validated = service.validateAndMaybeRenew("raw");
        assertNotNull(validated);
        assertFalse(validated.renewed());
        verify(mapper, never()).updateRenewedAt(anyLong(), anyLong(), anyLong());
    }

    @Test
    void remainingMaxAgeUsesAbsoluteExpiry() {
        AuthSessionBean row = new AuthSessionBean();
        row.setExpiresAt(System.currentTimeMillis() + 90_000);
        assertEquals(90, service.remainingMaxAgeSeconds(row));
    }
}
