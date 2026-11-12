package com.beyondzy.demo.session.impl;

import com.beyondzy.demo.mapper.session.AgentSessionMapper;
import com.beyondzy.demo.session.AgentSessionService;
import com.beyondzy.demo.session.SessionObjectService;
import com.beyondzy.demo.support.DbTimes;
import io.agentscope.core.session.SessionKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

/** 用户会话目录服务实现。 */
@Service
public class AgentSessionServiceImpl implements AgentSessionService {
    private final AgentSessionMapper mapper;
    private final SessionObjectService objects;

    public AgentSessionServiceImpl(AgentSessionMapper mapper, SessionObjectService objects) {
        this.mapper = mapper;
        this.objects = objects;
    }

    @Override
    public void register(long userId, String agentId, String sessionId) {
        mapper.insertIfAbsent(userId, agentId, sessionId, DbTimes.now());
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

    @Override
    public String title(long userId, String agentId, String sessionId) {
        var value = objects.read(userId, titlePath(agentId, sessionId));
        return value == null ? null : new String(value.bytes(), StandardCharsets.UTF_8);
    }

    @Override
    public void rename(long userId, String agentId, String sessionId, String title) {
        String path = titlePath(agentId, sessionId);
        byte[] bytes = title.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 16; attempt++) {
            var current = objects.read(userId, path);
            if (objects.compareAndSet(userId, path, current == null ? 0 : current.version(), bytes)) return;
        }
        throw new IllegalStateException("会话名称更新冲突，请重试");
    }

    private static String titlePath(String agentId, String sessionId) {
        return "thread-titles/" + agentId + "/" + sessionId;
    }
}
