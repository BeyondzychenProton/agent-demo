package com.beyondzy.demo.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.AtomicSessionStorage;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 基于 MySQL 原子存储实现 AgentScope 会话日志持久化，并按用户隔离会话命名空间。 */
@Component
public class MySqlSessionLogStore implements SessionLogStore {
    private final JdbcTemplate jdbc;

    public MySqlSessionLogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SessionLog open(SessionKey key, RuntimeContext context) {
        Objects.requireNonNull(key, "会话标识不能为空");
        long userId = parseUserId(key.userId());
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO agent_session(user_id, agent_id, session_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE updated_at=VALUES(updated_at)
                """, userId, key.agentId(), key.sessionId(), now, now);
        return new JournalSessionLog(new MySqlAtomicSessionStorage(userId), key.storagePath());
    }

    @Override
    public List<SessionKey> list(RuntimeContext context) {
        String user = context == null ? null : context.getUserId();
        if (user == null || user.isBlank()) return List.of();
        long userId = parseUserId(user);
        return jdbc.query("SELECT agent_id, session_id FROM agent_session WHERE user_id=? ORDER BY created_at DESC",
                (rs, row) -> new SessionKey(user, rs.getString("agent_id"), rs.getString("session_id")), userId);
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
            List<Value> rows = jdbc.query("SELECT version, payload FROM agent_session_object WHERE user_id=? AND object_path=?",
                    (rs, row) -> new Value(rs.getLong("version"), rs.getBytes("payload")), userId, path);
            return rows.isEmpty() ? null : rows.getFirst();
        }

        @Override
        public boolean compareAndSet(String path, long expectedVersion, byte[] bytes) {
            if (expectedVersion == 0) {
                try {
                    long now = System.currentTimeMillis();
                    jdbc.update("INSERT INTO agent_session_object(user_id, object_path, version, payload, created_at, updated_at) VALUES (?, ?, 1, ?, ?, ?)",
                            userId, path, bytes, now, now);
                    return true;
                } catch (DuplicateKeyException conflict) {
                    return false;
                }
            }
            return jdbc.update("UPDATE agent_session_object SET version=version+1, payload=?, updated_at=? WHERE user_id=? AND object_path=? AND version=?",
                    bytes, System.currentTimeMillis(), userId, path, expectedVersion) == 1;
        }
    }
}
