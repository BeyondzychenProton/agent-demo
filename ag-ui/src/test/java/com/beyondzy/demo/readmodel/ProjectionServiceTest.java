package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.session.ChatSessions;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实日志验证增量累加器在批次及进程边界上的行为。 */
@org.junit.jupiter.api.Disabled("旧本地存储测试装配已移除，待改用隔离的 MySQL 测试库")
class ProjectionServiceTest {
    @TempDir Path workspace;
    static class ModelStub implements Model {
        public String getModelName() { return "展示投影测试"; }
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new AssertionError("读取历史不得调用模型"));
        }
    }
    static SessionEvent event(long seq, String type, Object data) {
        return new SessionEvent(1, "event-" + seq, seq, System.currentTimeMillis(), type, "run", "turn", true, ReadModelStore.json(data));
    }
    static SessionEvent chunk(long seq, String text) {
        return event(seq, "model/chunk", Map.of("modelCallId", "model", "chunk", Map.of("id", "answer",
                "content", List.of(Map.of("type", "text", "text", text)))));
    }
    void append(ChatSessions sessions, SessionEvent... events) {
        var log = sessions.log("thread"); var writer = log.acquire("writer", Duration.ofSeconds(10));
        try { log.commit(writer, UUID.randomUUID().toString(), log.head().seq(), List.of(events)); }
        finally { log.release(writer); }
    }

    @Test void 部分消息重开后只追加新增内容且旧页保持原样() {
        String old;
        try (var sessions = new ChatSessions(workspace, new ModelStub())) {
            append(sessions, chunk(1, "第一段")); sessions.projection().sync("thread");
            var page = sessions.projection().store().page("thread", 50, null);
            old = page.eventCursor();
            assertEquals("第一段", page.items().get(0).item().get("text"));
        }
        try (var sessions = new ChatSessions(workspace, new ModelStub())) {
            append(sessions, chunk(2, "第二段")); sessions.projection().sync("thread");
            var page = sessions.projection().store().page("thread", 50, null);
            assertEquals(1, page.items().size());
            assertEquals("第一段第二段", page.items().get(0).item().get("text"));
            assertEquals(1, sessions.projection().store().changes("thread", old).size());
            long revision = sessions.projection().store().state("thread").revision();
            sessions.projection().sync("thread");
            assertEquals(revision, sessions.projection().store().state("thread").revision());
        }
    }

    @Test void 草稿与落定消息归为同一项且与旧快照一致() {
        try (var sessions = new ChatSessions(workspace, new ModelStub())) {
            append(sessions, chunk(1, "草稿")); sessions.projection().sync("thread");
            var finalMessage = Msg.builder().id("answer").role(MsgRole.ASSISTANT).content(TextBlock.builder().text("完整答案").build()).build();
            append(sessions, event(2, "message/assistant", Map.of("message", finalMessage)), event(3, "run/end", Map.of("status", "completed")));
            sessions.projection().sync("thread");
            var actual = sessions.projection().store().page("thread", 50, null);
            var expected = sessions.view("thread").view();
            assertEquals(1, actual.items().size());
            assertEquals(ReadModelStore.json(expected.get(0)), ReadModelStore.json(actual.items().get(0).item()));
        }
    }

    @Test void 工具参数分片跨检查点保持完整() {
        var first = event(1, "model/chunk", Map.of("modelCallId", "model", "chunk", Map.of("id", "answer", "content", List.of(
                Map.of("type", "tool_use", "id", "call", "name", "lookup_order", "content", "{\"order_id\":")))));
        var second = event(2, "model/chunk", Map.of("modelCallId", "model", "chunk", Map.of("id", "answer", "content", List.of(
                Map.of("type", "tool_use", "id", "call", "content", "\"A1001\"}")))));
        var source = new ChatItems(); source.apply(first);
        var restored = new ChatItems(); restored.restore(ReadModelStore.decode(ReadModelStore.json(source.checkpoint()), ChatItems.Checkpoint.class));
        source.apply(second); restored.apply(second);
        var expected = source.drainChanges(); var actual = restored.drainChanges();
        assertEquals(ReadModelStore.json(expected), ReadModelStore.json(actual));
    }
}
