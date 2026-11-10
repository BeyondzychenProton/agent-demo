package com.beyondzy.demo.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beyondzy.demo.auth.cookie.AuthCookieSupport;
import com.beyondzy.demo.auth.security.SessionTokenAuthenticationManager;
import com.beyondzy.demo.auth.service.AuthSessionService;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.support.DbTimes;
import com.beyondzy.demo.user.UserService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

/** 内省 HTTP 契约：机器密钥 + Bearer 会话；无效会话 401。 */
class SessionIntrospectControllerTest {

    private AuthSessionService sessions;
    private UserService users;
    private SessionIntrospectController controller;

    @BeforeEach
    void setUp() {
        sessions = mock(AuthSessionService.class);
        users = mock(UserService.class);
        SessionTokenAuthenticationManager manager =
                new SessionTokenAuthenticationManager(sessions, users, mock(AuthCookieSupport.class));
        SessionIntrospectionService service = new SessionIntrospectionService(manager);
        controller = new SessionIntrospectController(service, "shared-dev-key");
    }

    @Test
    void introspectWithMachineKeyAndBearerReturnsPhone() throws Exception {
        AuthSessionBean row = new AuthSessionBean();
        row.setId(3L);
        row.setUserId(9L);
        row.setExpiresAt(DbTimes.now().plusDays(1));
        row.setRenewedAt(DbTimes.now());
        when(sessions.validateReadOnly("sess-raw")).thenReturn(
                new AuthSessionService.ValidatedSession(row, false));
        UserBean user = new UserBean();
        user.setId(9L);
        user.setPhone("13900002222");
        user.setName("Bob");
        when(users.findById(9L)).thenReturn(user);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/internal/identity/session/introspect")
                .header("X-Identity-Connector-Key", "shared-dev-key")
                .header("Authorization", "Bearer sess-raw")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(controller.introspect(exchange))
                .assertNext(body -> {
                    assertEquals(Boolean.TRUE, body.get("valid"));
                    assertEquals("9", body.get("userId"));
                    assertEquals("13900002222", body.get("phone"));
                    assertEquals("Bob", body.get("name"));
                    assertEquals(3L, ((Number) body.get("authSessionId")).longValue());
                })
                .verifyComplete();
    }

    @Test
    void acceptsGovernanceBasicMachineCredentialHeader() {
        when(sessions.validateReadOnly("sess-raw")).thenReturn(null);
        String basic = Base64.getEncoder()
                .encodeToString("app-key:shared-dev-key".getBytes(StandardCharsets.UTF_8));
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/internal/identity/session/introspect")
                .header("X-Client-Authorization", "Basic " + basic)
                .header("Authorization", "Bearer sess-raw")
                .build();

        StepVerifier.create(controller.introspect(MockServerWebExchange.from(request)))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof ResponseStatusException);
                    assertEquals(HttpStatus.UNAUTHORIZED, ((ResponseStatusException) error).getStatusCode());
                })
                .verify();
    }

    @Test
    void rejectsMissingMachineKey() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/internal/identity/session/introspect")
                .header("Authorization", "Bearer sess-raw")
                .build();
        StepVerifier.create(controller.introspect(MockServerWebExchange.from(request)))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof ResponseStatusException);
                    assertEquals(HttpStatus.UNAUTHORIZED, ((ResponseStatusException) error).getStatusCode());
                })
                .verify();
    }

    @Test
    void rejectsMissingBearerEvenWithMachineKey() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/internal/identity/session/introspect")
                .header("X-Identity-Connector-Key", "shared-dev-key")
                .build();
        StepVerifier.create(controller.introspect(MockServerWebExchange.from(request)))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof ResponseStatusException);
                    assertEquals(HttpStatus.UNAUTHORIZED, ((ResponseStatusException) error).getStatusCode());
                })
                .verify();
    }
}
