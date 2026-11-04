package com.beyondzy.demo.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beyondzy.demo.domain.auth.AuthSessionBean;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceTest {
    @Test
    void rejectsBadPassword() {
        UserService users = mock(UserService.class);
        AuthSessionService sessions = mock(AuthSessionService.class);
        UserBean user = new UserBean();
        user.setId(1L);
        user.setPhone("13636528157");
        when(users.findByPhone("13636528157")).thenReturn(user);
        when(users.matchesPassword(user, "wrong")).thenReturn(false);
        AuthService auth = new AuthService(users, sessions);
        assertThrows(ResponseStatusException.class, () -> auth.login("13636528157", "wrong"));
    }

    @Test
    void loginReturnsCreatedSession() {
        UserService users = mock(UserService.class);
        AuthSessionService sessions = mock(AuthSessionService.class);
        UserBean user = new UserBean();
        user.setId(1L);
        user.setName("演示用户");
        user.setPhone("13636528157");
        when(users.findByPhone("13636528157")).thenReturn(user);
        when(users.matchesPassword(user, "13636528157")).thenReturn(true);
        AuthSessionBean row = new AuthSessionBean();
        row.setId(8L);
        row.setExpiresAt(System.currentTimeMillis() + 1000);
        when(sessions.create(1L)).thenReturn(new AuthSessionService.CreatedSession("raw-token", row));
        AuthService auth = new AuthService(users, sessions);
        var result = auth.login("13636528157", "13636528157");
        assertEquals("raw-token", result.session().rawToken());
        assertEquals(1L, result.user().getId());
    }
}
