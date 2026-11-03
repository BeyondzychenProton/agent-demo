package com.beyondzy.demo.web.agui;

import static org.junit.jupiter.api.Assertions.*;
import com.beyondzy.demo.session.ChatSessions;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** 覆盖浏览器实际使用的 AG-UI 提交入口，不只验证独立的审批接口。 */
@org.junit.jupiter.api.Disabled("旧本地存储测试装配已移除，待改用隔离的 MySQL 测试库")
class AguiQueuedRunTest {
    @TempDir Path workspace;
    static class TextModel implements Model {
        public String getModelName() { return "本地流式回归模型"; }
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString())
                    .content(List.of(TextBlock.builder().text("排队后的正常回复").build())).finishReason("stop").build());
        }
    }
    static Map<String, Object> request(String thread, String run) {
        return Map.of("threadId", thread, "runId", run,
                "messages", List.of(Map.of("role", "user", "content", "你好")));
    }

    @Test void 命令已接收但执行尚未启动也应返回开始帧() {
        try (var sessions = new ChatSessions(workspace, new TextModel())) {
            var log = sessions.log("queued");
            // 占住执行写入资格，确定性地让 submit 返回 queued、runId=null。
            var writer = log.acquire("测试暂缓调度", Duration.ofSeconds(30));
            try {
                var first = new AguiService(sessions).run("chat", request("queued", "client-run"))
                        .next().block(Duration.ofSeconds(5));
                assertNotNull(first);
                assertTrue(first.data().contains("RUN_STARTED"));
                assertTrue(first.data().contains("client-run"));
            } finally { log.release(writer); }
        }
    }
    private void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < limit) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(30);
        }
        fail("等待命令状态超时");
    }

    @Test void 排队后启动能收到回复并正常结束() throws Exception {
        try (var sessions = new ChatSessions(workspace, new TextModel())) {
            var log = sessions.log("queued");
            var writer = log.acquire("测试暂缓调度", Duration.ofSeconds(30));
            var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
            java.util.concurrent.CompletableFuture<List<org.springframework.http.codec.ServerSentEvent<String>>> future;
            try {
                future = new AguiService(sessions).run("chat", request("queued", "client-run"))
                        .doOnNext(event -> received.add(event.data())).collectList().toFuture();
                waitFor(() -> !received.isEmpty());
                assertEquals("queued", sessions.latestTask("queued").status());
                assertNull(sessions.latestTask("queued").runId());
                assertFalse(future.isDone(), "排队时不能提前宣告流结束");
            } finally { log.release(writer); }
            var events = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(events.stream().anyMatch(e -> e.data().contains("排队后的正常回复")));
            assertTrue(events.getLast().data().contains("RUN_FINISHED"));
            assertTrue(events.getLast().data().contains("client-run"));
        }
    }

    @Test void 重建适配器后仍可续接尚未启动的命令() throws Exception {
        try (var sessions = new ChatSessions(workspace, new TextModel())) {
            var log = sessions.log("reconnect");
            var writer = log.acquire("测试重连前暂缓调度", Duration.ofSeconds(30));
            java.util.concurrent.CompletableFuture<List<org.springframework.http.codec.ServerSentEvent<String>>> future;
            try {
                new AguiService(sessions).run("chat", request("reconnect", "original-run"))
                        .next().block(Duration.ofSeconds(5));
                // 新适配器没有旧进程内映射，也必须能从持久命令找回排队任务。
                var reconnected = new AguiService(sessions);
                future = reconnected.connect("reconnect", "reconnect-run").collectList().toFuture();
                assertNull(sessions.latestTask("reconnect").runId());
            } finally { log.release(writer); }
            var events = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(events.stream().anyMatch(e -> e.data().contains("排队后的正常回复")));
            assertTrue(events.getLast().data().contains("RUN_FINISHED"));
        }
    }

    @Test void 连续快速任务各自收到一次回复和结束帧() {
        try (var sessions = new ChatSessions(workspace, new TextModel())) {
            var service = new AguiService(sessions);
            for (int i = 0; i < 3; i++) {
                String clientRun = "fast-run-" + i;
                var events = service.run("chat", request("fast", clientRun))
                        .collectList().block(Duration.ofSeconds(15));
                assertNotNull(events);
                assertEquals(1, events.stream().filter(e -> e.data().contains("RUN_FINISHED")).count());
                assertEquals(1, events.stream().filter(e -> e.data().contains("排队后的正常回复")).count());
                assertTrue(events.getLast().data().contains(clientRun));
            }
        }
    }

    @Test void 浏览器提交入口可挂起审批并续接批准后的回复() throws Exception {
        Model model = new TextModel() {
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (messages.stream().anyMatch(m -> m.hasContentBlocks(ToolUseBlock.class)))
                    return super.stream(messages, tools, options);
                var input = Map.<String, Object>of("path", "notes/agui.md", "content", "浏览器审批入口验证");
                return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString())
                        .content(List.of(ToolUseBlock.builder().id("agui-approval").name("write_note")
                                .input(input).content(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(input)).build()))
                        .finishReason("tool_calls").build());
            }
        };
        try (var sessions = new ChatSessions(workspace, model)) {
            var service = new AguiService(sessions);
            var suspended = service.run("chat", request("approval", "first-run"))
                    .collectList().block(Duration.ofSeconds(15));
            assertTrue(suspended.getLast().data().contains("RUN_FINISHED"));
            waitFor(() -> sessions.snapshot("approval").activeRun() == null);
            assertFalse(java.nio.file.Files.exists(workspace.resolve("notes/agui.md")));
            var pending = sessions.snapshot("approval").pending();
            assertEquals(1, pending.size());
            String requestId = (String) pending.getFirst().data().get("requestId");
            sessions.answer("approval", requestId, "approve", "");
            var resumed = new AguiService(sessions).connect("approval", "approval-connect")
                    .collectList().block(Duration.ofSeconds(15));
            assertNotNull(resumed);
            assertTrue(resumed.getLast().data().contains("RUN_FINISHED"));
            assertTrue(resumed.stream().anyMatch(e -> e.data().contains("排队后的正常回复")));
            assertTrue(java.nio.file.Files.exists(workspace.resolve("notes/agui.md")));
        }
    }

}
