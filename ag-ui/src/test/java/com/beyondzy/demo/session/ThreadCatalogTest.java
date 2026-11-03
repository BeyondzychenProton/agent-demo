package com.beyondzy.demo.session;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ThreadCatalogTest {
    @Test
    void derivesTitleOrderAndRestartFromCommittedInbox() {
        var first = new Log();
        first.inbox.events.add(event(1, 1000, "inbox/accepted",
                new SessionInbox.Command("c1", "submit", "t1", List.of(user("第一条消息")), List.of())));
        var second = new Log();
        second.inbox.events.add(event(1, 2000, "inbox/accepted",
                new SessionInbox.Command("c2", "submit", "t2", List.of(user("第二条消息")), List.of())));
        var ids = List.of("first", "second", "phantom");
        var logs = Map.of("first", first, "second", second, "phantom", new Log());
        var catalog = new ThreadCatalog(() -> ids, logs::get, null);

        first.events.add(event(1, 1050, "run/start", Map.of()));
        first.events.add(event(2, 1100, "run/end", Map.of("status", "completed")));
        assertEquals("第一条消息", catalog.get("first").title());
        assertEquals(0, first.earlyReads);
        assertEquals(1000, catalog.get("first").createdAt());
        assertNull(catalog.get("phantom"));
        var firstPage = catalog.page(1, null);
        assertEquals("second", firstPage.threads().getFirst().id());
        assertNotNull(firstPage.nextCursor());
        assertEquals("first", catalog.page(1, firstPage.nextCursor()).threads().getFirst().id());
        assertThrows(IllegalArgumentException.class, () -> catalog.page(1, "bad"));

        var reopened = new ThreadCatalog(() -> ids, logs::get, null);
        assertEquals(catalog.get("first"), reopened.get("first"));
        first.events.add(event(3, 3000, "run/end", Map.of("status", "completed")));
        assertEquals("first", reopened.page(1, null).threads().getFirst().id());
        assertEquals("第一条消息", reopened.get("first").title());
    }

    @Test
    void legacyExecutionFactProvidesTitleWithoutInbox() {
        var log = new Log();
        log.events.add(event(1, 1500, "message/user", Map.of("message", user("旧会话"))));
        var catalog = new ThreadCatalog(() -> List.of("legacy"), id -> log, null);
        assertEquals("旧会话", catalog.get("legacy").title());
    }

    private static Msg user(String text) {
        return Msg.builder().id("m-" + text).role(MsgRole.USER).textContent(text).build();
    }

    private static SessionEvent event(long seq, long at, String type, Object payload) {
        return new SessionEvent(1, "e" + seq, seq, at, type, null, "t", false,
                JsonUtils.getJsonCodec().toJson(payload));
    }

    private static final class Log implements SessionLog {
        final List<SessionEvent> events = new ArrayList<>();
        int earlyReads;
        final Log inbox;
        Log() { this(true); }
        private Log(boolean withInbox) { inbox = withInbox ? new Log(false) : this; }
        @Override public SessionLog inbox() { return inbox; }
        @Override public Head head() { return new Head(events.size(), 0, null, 0, null); }
        @Override public List<SessionEvent> readAfter(long after, int limit) {
            if (after == 0) earlyReads++;
            return events.stream().filter(event -> event.seq() > after).limit(limit).toList();
        }
        @Override public Writer acquire(String owner, Duration lease) { throw new UnsupportedOperationException(); }
        @Override public void renew(Writer writer, Duration lease) { throw new UnsupportedOperationException(); }
        @Override public void release(Writer writer) { throw new UnsupportedOperationException(); }
        @Override public Head commit(Writer writer, String batch, long seq, List<SessionEvent> events) {
            throw new UnsupportedOperationException();
        }
    }
}
