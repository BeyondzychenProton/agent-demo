package com.beyondzy.demo.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beyondzy.demo.auth.cookie.AuthCookieSupport;
import com.beyondzy.demo.auth.security.SessionTokenAuthenticationManager;
import com.beyondzy.demo.auth.service.AuthSessionService;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.mapper.auth.AuthSessionMapper;
import com.beyondzy.demo.support.DbTimes;
import com.beyondzy.demo.user.UserService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.web.server.ResponseStatusException;

/** 机器会话内省：phone 与 AuthPrincipal 同源；登出/过期失败；两用户并发互不串号。 */
class SessionIntrospectionServiceTest {

    private AuthSessionService sessions;
    private UserService users;
    private SessionTokenAuthenticationManager authenticationManager;
    private SessionIntrospectionService service;

    @BeforeEach
    void setUp() {
        sessions = mock(AuthSessionService.class);
        users = mock(UserService.class);
        authenticationManager = new SessionTokenAuthenticationManager(sessions, users, mock(AuthCookieSupport.class));
        service = new SessionIntrospectionService(authenticationManager);
    }

    @Test
    void validSessionReturnsPhoneFromAppUser() {
        LocalDateTime expires = DbTimes.now().plusDays(1);
        AuthSessionBean row = sessionRow(11L, 7L, expires);
        when(sessions.validateReadOnly("raw-session-token")).thenReturn(
                new AuthSessionService.ValidatedSession(row, false));
        UserBean user = new UserBean();
        user.setId(7L);
        user.setPhone("13800001111");
        user.setName("Alice");
        when(users.findById(7L)).thenReturn(user);

        SessionIntrospectionService.Result result = service.introspect("raw-session-token");

        assertTrue(result.valid());
        assertEquals("7", result.userId());
        assertEquals("13800001111", result.phone());
        assertEquals("Alice", result.name());
        assertEquals(11L, result.authSessionId());
        assertEquals(DbTimes.toEpochMilli(expires), result.expiresAt());
        verify(sessions, never()).validateAndMaybeRenew(any());
    }

    @Test
    void introspectDoesNotMutateSessionTimestampsEvenWhenRenewWouldBeDue() {
        AuthSessionMapper mapper = mock(AuthSessionMapper.class);
        AuthSessionService realSessions =
                new AuthSessionService(mapper, Duration.ofDays(7), Duration.ofDays(1));
        UserService realUsers = mock(UserService.class);
        SessionIntrospectionService realService = new SessionIntrospectionService(
                new SessionTokenAuthenticationManager(realSessions, realUsers, mock(AuthCookieSupport.class)));

        LocalDateTime expires = DbTimes.now().plusDays(5);
        LocalDateTime renewedAt = DbTimes.now().minusDays(2);
        LocalDateTime gmtModified = renewedAt.minusHours(1);
        AuthSessionBean row = sessionRow(21L, 8L, expires);
        row.setTokenHash(com.beyondzy.demo.auth.cookie.TokenHasher.sha256("due-renew-token"));
        row.setRenewedAt(renewedAt);
        row.setGmtModified(gmtModified);
        when(mapper.selectOne(ArgumentMatchers.any())).thenReturn(row);
        UserBean user = new UserBean();
        user.setId(8L);
        user.setPhone("13800008888");
        user.setName("Readonly");
        when(realUsers.findById(8L)).thenReturn(user);

        SessionIntrospectionService.Result result = realService.introspect("due-renew-token");

        assertTrue(result.valid());
        assertEquals("13800008888", result.phone());
        assertEquals(renewedAt, row.getRenewedAt());
        assertEquals(gmtModified, row.getGmtModified());
        assertEquals(expires, row.getExpiresAt());
        verify(mapper, never()).updateRenewedAt(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void concurrentUsersKeepDistinctPhones() throws Exception {
        stubUser("token-a", 101L, 1L, "13800001001", "UserA");
        stubUser("token-b", 102L, 2L, "13800001002", "UserB");

        ConcurrentHashMap<String, String> phones = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.execute(() -> {
            await(start);
            try {
                phones.put("a", service.introspect("token-a").phone());
            } finally {
                done.countDown();
            }
        });
        pool.execute(() -> {
            await(start);
            try {
                phones.put("b", service.introspect("token-b").phone());
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals("13800001001", phones.get("a"));
        assertEquals("13800001002", phones.get("b"));
    }

    @Test
    void logoutMakesIntrospectionFail() {
        when(sessions.validateReadOnly("gone-token")).thenReturn(null);
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> service.introspect("gone-token"));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void expiredSessionMakesIntrospectionFail() {
        when(sessions.validateReadOnly("expired-token")).thenReturn(null);
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> service.introspect("expired-token"));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void missingUserMakesIntrospectionFail() {
        AuthSessionBean row = sessionRow(31L, 99L, DbTimes.now().plusDays(1));
        when(sessions.validateReadOnly("orphan-token")).thenReturn(
                new AuthSessionService.ValidatedSession(row, false));
        when(users.findById(99L)).thenReturn(null);
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> service.introspect("orphan-token"));
        assertEquals(401, ex.getStatusCode().value());
        verify(sessions, never()).validateAndMaybeRenew(any());
    }

    @Test
    void blankTokenIsInvalid() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.introspect(" "));
        assertEquals(401, ex.getStatusCode().value());
    }

    private void stubUser(String token, long sessionId, long userId, String phone, String name) {
        AuthSessionBean row = sessionRow(sessionId, userId, DbTimes.now().plusDays(1));
        when(sessions.validateReadOnly(token)).thenReturn(new AuthSessionService.ValidatedSession(row, false));
        UserBean user = new UserBean();
        user.setId(userId);
        user.setPhone(phone);
        user.setName(name);
        when(users.findById(userId)).thenReturn(user);
    }

    private static AuthSessionBean sessionRow(long id, long userId, LocalDateTime expires) {
        AuthSessionBean row = new AuthSessionBean();
        row.setId(id);
        row.setUserId(userId);
        row.setExpiresAt(expires);
        row.setRenewedAt(DbTimes.now());
        return row;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
