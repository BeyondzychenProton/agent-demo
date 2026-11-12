package com.beyondzy.demo.session;

import com.beyondzy.demo.service.session.AgentSessionService;

import com.beyondzy.demo.exception.session.SessionNotFoundException;

/** 会话归属校验：已登记到其他用户的会话对当前用户不可见。 */
public final class SessionAccess {
    private SessionAccess() {}

    public static void assertOwnedOrNew(AgentSessionService agentSessions, String agentId,
                                        String sessionId, String userId) {
        if (agentSessions == null) return;
        Long owner = agentSessions.findUserId(agentId, sessionId);
        if (owner == null) return;
        if (!String.valueOf(owner).equals(userId))
            throw new SessionNotFoundException("会话不存在");
    }
}
