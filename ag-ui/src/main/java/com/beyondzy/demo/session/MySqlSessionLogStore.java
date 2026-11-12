package com.beyondzy.demo.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.AtomicSessionStorage;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import com.beyondzy.demo.service.session.AgentSessionService;
import com.beyondzy.demo.service.session.SessionObjectService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 基于 MySQL 原子存储实现 AgentScope 会话日志持久化，并按用户隔离会话命名空间。 */
@Component
public class MySqlSessionLogStore implements SessionLogStore {
    private final AgentSessionService agentSessionService;
    private final SessionObjectService sessionObjectService;

    public MySqlSessionLogStore(AgentSessionService agentSessionService,
                                SessionObjectService sessionObjectService) {
        this.agentSessionService = agentSessionService;
        this.sessionObjectService = sessionObjectService;
    }

    @Override
    public SessionLog open(SessionKey key, RuntimeContext context) {
        Objects.requireNonNull(key, "会话标识不能为空");
        long userId = parseUserId(key.userId());
        agentSessionService.register(userId, key.agentId(), key.sessionId());
        return new JournalSessionLog(new MySqlAtomicSessionStorage(userId), key.storagePath());
    }

    @Override
    public List<SessionKey> list(RuntimeContext context) {
        String user = context == null ? null : context.getUserId();
        if (user == null || user.isBlank()) return List.of();
        long userId = parseUserId(user);
        return agentSessionService.list(userId, user);
    }

    private static long parseUserId(String userId) {
        try {
            long id = Long.parseLong(userId);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("MySQL 会话存储要求 userId 为正整数用户主键", error);
        }
    }

    /** 为 JournalSessionLog 提供跨进程、跨实例的 MySQL compare-and-set 能力。 */
    private final class MySqlAtomicSessionStorage implements AtomicSessionStorage {
        private final long userId;

        private MySqlAtomicSessionStorage(long userId) { this.userId = userId; }

        @Override
        public Value read(String path) {
            return sessionObjectService.read(userId, path);
        }

        @Override
        public boolean compareAndSet(String path, long expectedVersion, byte[] bytes) {
            return sessionObjectService.compareAndSet(userId, path, expectedVersion, bytes);
        }
    }
}
