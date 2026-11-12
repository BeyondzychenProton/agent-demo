package com.beyondzy.demo.session.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.beyondzy.demo.mapper.session.AgentSessionMapper;
import com.beyondzy.demo.session.SessionObjectService;
import io.agentscope.core.session.AtomicSessionStorage;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSessionTitleTest {
    @Test
    void storesAndReplacesTitlesIndependentlyForEachUser() {
        var objects = new MemoryObjects();
        var service = new AgentSessionServiceImpl(mock(AgentSessionMapper.class), objects);
        assertNull(service.title(1, "agent", "session"));
        service.rename(1, "agent", "session", "产品会议");
        service.rename(2, "agent", "session", "另一用户");
        service.rename(1, "agent", "session", "更新后的会议");
        var reopened = new AgentSessionServiceImpl(mock(AgentSessionMapper.class), objects);
        assertEquals("更新后的会议", reopened.title(1, "agent", "session"));
        assertEquals("另一用户", reopened.title(2, "agent", "session"));
        assertNull(reopened.title(1, "agent", "other"));
    }

    private static final class MemoryObjects implements SessionObjectService {
        private final Map<String, AtomicSessionStorage.Value> values = new HashMap<>();
        public AtomicSessionStorage.Value read(long userId, String path) { return values.get(userId + ":" + path); }
        public boolean compareAndSet(long userId, String path, long expected, byte[] bytes) {
            String key = userId + ":" + path;
            var current = values.get(key);
            if ((current == null ? 0 : current.version()) != expected) return false;
            values.put(key, new AtomicSessionStorage.Value(expected + 1, bytes));
            return true;
        }
    }
}
