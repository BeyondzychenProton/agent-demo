package com.beyondzy.demo.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beyondzy.demo.auth.support.SessionNotFoundException;
import org.junit.jupiter.api.Test;

class SessionAccessTest {
    @Test
    void foreignSessionIsNotFound() {
        AgentSessionService agentSessions = mock(AgentSessionService.class);
        when(agentSessions.findUserId("session-chat", "abc")).thenReturn(99L);
        assertThrows(SessionNotFoundException.class,
                () -> SessionAccess.assertOwnedOrNew(agentSessions, "session-chat", "abc", "1"));
    }

    @Test
    void unregisteredSessionIsAllowed() {
        AgentSessionService agentSessions = mock(AgentSessionService.class);
        when(agentSessions.findUserId("session-chat", "new-thread")).thenReturn(null);
        assertDoesNotThrow(() -> SessionAccess.assertOwnedOrNew(
                agentSessions, "session-chat", "new-thread", "1"));
    }

    @Test
    void ownerCanAccess() {
        AgentSessionService agentSessions = mock(AgentSessionService.class);
        when(agentSessions.findUserId("session-chat", "mine")).thenReturn(1L);
        assertDoesNotThrow(() -> SessionAccess.assertOwnedOrNew(
                agentSessions, "session-chat", "mine", "1"));
    }
}
