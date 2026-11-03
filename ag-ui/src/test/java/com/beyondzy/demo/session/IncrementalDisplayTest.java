package com.beyondzy.demo.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class IncrementalDisplayTest {
    @Test
    void mergesNewMessageAndTurnWithoutReplayingOldHistory() {
        Msg message = Msg.builder().id("m2").role(MsgRole.USER).textContent("新增消息").build();
        List<SessionEvent> events = List.of(
                event(2, "message/user", null, Map.of("message", message)),
                event(3, "run/start", "r2", Map.of()),
                event(4, "run/end", "r2", Map.of("status", "finished")));
        SessionLog log = new TestLog(4, events);
        ThreadCatalog.DisplaySnapshot old = new ThreadCatalog.DisplaySnapshot(1,
                List.of(new ThreadCatalog.DisplayMessage("m1", "user", "原有消息")), List.of());

        ThreadCatalog.DisplaySnapshot result = ChatSessions.incrementalDisplay(log, log.head(), old);

        assertEquals(4, result.seq());
        assertEquals(List.of("m1", "m2"), result.messages().stream()
                .map(ThreadCatalog.DisplayMessage::id).toList());
        assertEquals("新增消息", result.messages().get(1).text());
        assertEquals(List.of(new ThreadCatalog.DisplayTurn("r2", "finished")), result.turns());
    }

    @Test
    void incompleteEventRangeFallsBackToFullRebuild() {
        SessionLog log = new TestLog(3, List.of(event(3, "run/end", "r2", Map.of())));
        ThreadCatalog.DisplaySnapshot old = new ThreadCatalog.DisplaySnapshot(1, List.of(), List.of());
        assertNull(ChatSessions.incrementalDisplay(log, log.head(), old));
    }

    private static SessionEvent event(long seq, String type, String runId, Map<String, Object> data) {
        return new SessionEvent(1, "e" + seq, seq, seq, type, runId, "t2", false,
                JsonUtils.getJsonCodec().toJson(data));
    }

    private record TestLog(long seq, List<SessionEvent> events) implements SessionLog {
        @Override public Head head() { return new Head(seq, 0, null, 0, null); }
        @Override public List<SessionEvent> readAfter(long after, int limit) {
            return events.stream().filter(event -> event.seq() > after).limit(limit).toList();
        }
        @Override public Writer acquire(String owner, Duration lease) {
            throw new UnsupportedOperationException();
        }
        @Override public void renew(Writer writer, Duration lease) {
            throw new UnsupportedOperationException();
        }
        @Override public void release(Writer writer) {
            throw new UnsupportedOperationException();
        }
        @Override public Head commit(Writer writer, String batch, long seq, List<SessionEvent> events) {
            throw new UnsupportedOperationException();
        }
    }
}
