package com.beyondzy.demo.session.impl;

import com.beyondzy.demo.mapper.session.AgentSessionMapper;
import com.beyondzy.demo.session.AgentSessionService;
import io.agentscope.core.session.SessionKey;
import java.util.List;
import org.springframework.stereotype.Service;

/** 用户会话目录服务实现。 */
@Service
public class AgentSessionServiceImpl implements AgentSessionService {
    private final AgentSessionMapper mapper;

    public AgentSessionServiceImpl(AgentSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void register(long userId, String agentId, String sessionId) {
        mapper.insertIfAbsent(userId, agentId, sessionId, System.currentTimeMillis());
    }

    @Override
    public List<SessionKey> list(long userId, String userIdText) {
        return mapper.findByUserId(userId).stream()
                .map(row -> new SessionKey(userIdText, row.getAgentId(), row.getSessionId()))
                .toList();
    }

    @Override
    public Long findUserId(String agentId, String sessionId) {
        return mapper.findUserId(agentId, sessionId);
    }
}
