package com.beyondzy.demo.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.auth.support.SubmitCredentialCapture;
import com.beyondzy.demo.auth.support.UserContext;
import com.beyondzy.demo.readmodel.ThreadView;
import com.beyondzy.demo.session.ChatSessions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 经 {@link ChatSessions} 真实入口验证：enqueue hold、提交上下文收口、跨用户清理、审批 resume、进程恢复。
 */
class ChatSessionsBindingProofTest {

    private static final String URI_V1 = "driver://mcp/tenant-a/demo/tool_v1";
    private static final String URI_V2 = "driver://mcp/tenant-a/demo/tool_v2";
    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";

    @AfterEach
    void clear() {
        RunCredentialRegistry.clearAll();
    }

    @Test
    void sameSessionConcurrentSubmitsKeepGenerationUntilEnqueued(@TempDir Path workspace) throws Exception {
        MutableGateway gateway = new MutableGateway(snap("snap_v1", "tool_v1", URI_V1));
        WorkspaceSessionLogStore store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        HoldModel model = new HoldModel();
        try (ChatSessions sessions = new ChatSessions(workspace, model, store, USER_A, gateway)) {
            assertEquals("snap_v1", sessions.governanceSnapshotId());
            CountDownLatch firstInModel = new CountDownLatch(1);
            CountDownLatch releaseModel = new CountDownLatch(1);
            model.blockUntil = releaseModel;
            model.onStream = firstInModel::countDown;

            Thread t1 = new Thread(
                    () -> asUser(USER_A, () -> sessions.submit("sess-concurrent", "req-c1", "并发一")),
                    "submit-1");
            t1.start();
            assertTrue(firstInModel.await(15, TimeUnit.SECONDS), "首任务应进入模型流并占住一代");
            assertEquals("snap_v1", asUser(USER_A, () -> sessions.boundAssemblySnapshotId("sess-concurrent")));

            Thread t2 = new Thread(
                    () -> asUser(USER_A, () -> sessions.submit("sess-concurrent", "req-c2", "并发二")),
                    "submit-2");
            t2.start();
            Thread.sleep(300);
            assertEquals("snap_v1", asUser(USER_A, () -> sessions.boundAssemblySnapshotId("sess-concurrent")));
            assertFalse(sessions.harnessGenerationClosed("snap_v1"));

            gateway.publish(snap("snap_v2", "tool_v2", URI_V2));
            sessions.refreshGovernanceAssembly();
            assertEquals("snap_v2", sessions.governanceSnapshotId());
            assertFalse(sessions.harnessGenerationClosed("snap_v1"), "入队/执行中不得关闭 v1");

            releaseModel.countDown();
            t1.join(Duration.ofSeconds(25));
            t2.join(Duration.ofSeconds(25));
            waitUntil(() -> {
                AgentSession.Task a = asUser(USER_A, () -> sessions.latestTask("sess-concurrent"));
                return a != null && a.settled();
            });
        }
    }

    @Test
    void otherUserAndStaleCleanupMustNotClearForeignOrReboundBinding(@TempDir Path workspace)
            throws Exception {
        MutableGateway gateway = new MutableGateway(snap("snap_v1", "tool_v1", URI_V1));
        WorkspaceSessionLogStore store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        ScriptModel model = new ScriptModel("tool_v1");
        try (ChatSessions sessions = new ChatSessions(workspace, model, store, USER_A, gateway)) {
            AgentSession.Task taskA =
                    asUser(USER_A, () -> sessions.submit("sess-a", "req-a", "用户A任务"));
            waitUntil(() -> asUser(USER_A, () -> sessions.latestTask("sess-a")).settled());
            assertNotNull(taskA);

            gateway.publish(snap("snap_v2", "tool_v2", URI_V2));
            sessions.refreshGovernanceAssembly();
            model.nextTool = "tool_v2";
            AgentSession.Task taskB =
                    asUser(USER_B, () -> sessions.submit("sess-b", "req-b", "用户B任务"));
            assertEquals("snap_v2", asUser(USER_B, () -> sessions.boundAssemblySnapshotId("sess-b")));
            // 过期/错版 clear 不得清 B 的 v2 绑定
            asUser(USER_A, () -> sessions.tryClearAssemblyBinding("sess-a", "snap_v1"));
            assertFalse(asUser(USER_B, () -> sessions.tryClearAssemblyBinding("sess-b", "snap_v1")));
            assertEquals("snap_v2", asUser(USER_B, () -> sessions.boundAssemblySnapshotId("sess-b")));

            waitUntil(() -> asUser(USER_B, () -> sessions.latestTask("sess-b")).settled());
            assertTrue(
                    gateway.calls.stream().anyMatch(c -> "snap_v2".equals(c.snapshotId())),
                    "B invoke 应带 v2, calls=" + gateway.calls);
            assertTrue(asUser(USER_B, () -> sessions.latestTask("sess-b")).settled());

            // 同会话换绑：旧版本 clearIfMatch 不得清掉新绑定
            model.nextTool = "tool_v2";
            asUser(USER_B, () -> sessions.submit("sess-b", "req-b2", "用户B再跑"));
            assertEquals("snap_v2", asUser(USER_B, () -> sessions.boundAssemblySnapshotId("sess-b")));
            assertFalse(asUser(USER_B, () -> sessions.tryClearAssemblyBinding("sess-b", "snap_v1")));
            assertEquals("snap_v2", asUser(USER_B, () -> sessions.boundAssemblySnapshotId("sess-b")));
            waitUntil(() -> asUser(USER_B, () -> sessions.latestTask("sess-b")).settled());
            assertTrue(asUser(USER_B, () -> sessions.latestTask("sess-b")).settled());
        }
    }

    @Test
    void approvalResumeInvokeStillCarriesV1AfterV2Adopt(@TempDir Path workspace) throws Exception {
        MutableGateway gateway = new MutableGateway(snap("snap_v1", "tool_v1", URI_V1));
        WorkspaceSessionLogStore store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        PhasedHitlModel model = new PhasedHitlModel();
        try (ChatSessions sessions = new ChatSessions(workspace, model, store, USER_A, gateway)) {
            asUser(USER_A, () -> sessions.submit("sess-hitl", "req-hitl", "请记录审批笔记"));
            waitUntil(() -> hasPending(sessions, USER_A, "sess-hitl"));
            assertEquals("snap_v1", asUser(USER_A, () -> sessions.boundAssemblySnapshotId("sess-hitl")));
            assertEquals("snap_v1", asUser(USER_A, () -> sessions.boundToolSnapshotId("sess-hitl", "tool_v1")));
            assertTrue(
                    model.lastTools.stream().anyMatch(t -> "tool_v1".equals(t.getName())),
                    "待审批时模型应看到 tool_v1");

            gateway.publish(snap("snap_v2", "tool_v2", URI_V2));
            sessions.refreshGovernanceAssembly();
            assertEquals("snap_v2", sessions.governanceSnapshotId());
            assertFalse(sessions.harnessGenerationClosed("snap_v1"));
            // current 已是 v2，但会话绑定的 Harness 代仍钉 v1
            assertEquals("snap_v1", asUser(USER_A, () -> sessions.boundToolSnapshotId("sess-hitl", "tool_v1")));

            model.phase.set("invoke_v1");
            AgentSession.Task before = asUser(USER_A, () -> sessions.latestTask("sess-hitl"));
            if (before != null && before.runId() != null) {
                RunCredentialRegistry.bindRun(USER_A, "sess-hitl", before.runId(), "tok-" + USER_A);
            }
            String requestId = pendingRequestId(sessions, USER_A, "sess-hitl");
            asUser(USER_A, () -> sessions.answer("sess-hitl", requestId, "reject", "验收"));
            waitUntil(() -> {
                AgentSession.Task t = asUser(USER_A, () -> sessions.latestTask("sess-hitl"));
                return t != null && t.settled();
            });
            AgentSession.Task done = asUser(USER_A, () -> sessions.latestTask("sess-hitl"));
            assertTrue(done.settled(), done.status() + " " + done.error());
            assertEquals("snap_v1", asUser(USER_A, () -> sessions.boundToolSnapshotId("sess-hitl", "tool_v1")));
            assertTrue(
                    model.lastTools.stream().anyMatch(t -> "tool_v1".equals(t.getName())),
                    "resume 模型工具列表仍为 v1 代");
            assertTrue(
                    model.lastTools.stream().noneMatch(t -> "tool_v2".equals(t.getName())),
                    "resume 不得混入 tool_v2");
            if (!gateway.calls.isEmpty()) {
                assertTrue(
                        gateway.calls.stream().allMatch(c -> "snap_v1".equals(c.snapshotId())),
                        "若发生 invoke，必须仍带 v1，calls=" + gateway.calls);
            }
        }
    }

    @Test
    void restartRecoveryRebuildsOriginalSnapshot(@TempDir Path workspace) throws Exception {
        MutableGateway gateway = new MutableGateway(snap("snap_v1", "tool_v1", URI_V1));
        WorkspaceSessionLogStore store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        ApprovalModel model = new ApprovalModel();
        String requestId;
        try (ChatSessions sessions = new ChatSessions(workspace, model, store, USER_A, gateway)) {
            asUser(USER_A, () -> sessions.submit("sess-recover", "req-recover", "待恢复审批"));
            waitUntil(() -> hasPending(sessions, USER_A, "sess-recover"));
            requestId = pendingRequestId(sessions, USER_A, "sess-recover");
            assertEquals(
                    "snap_v1", asUser(USER_A, () -> sessions.boundAssemblySnapshotId("sess-recover")));
            gateway.publish(snap("snap_v2", "tool_v2", URI_V2));
        }

        try (ChatSessions reopened = new ChatSessions(workspace, model, store, USER_A, gateway)) {
            assertEquals("snap_v2", reopened.governanceSnapshotId());
            assertEquals(
                    "snap_v1", asUser(USER_A, () -> reopened.boundAssemblySnapshotId("sess-recover")));
            waitUntil(() -> hasPending(reopened, USER_A, "sess-recover"));
            asUser(USER_A, () -> reopened.answer("sess-recover", requestId, "reject", "恢复后结束"));
            waitUntil(() -> {
                AgentSession.Task t = asUser(USER_A, () -> reopened.latestTask("sess-recover"));
                return t != null && t.settled();
            });
            AgentSession.Task done = asUser(USER_A, () -> reopened.latestTask("sess-recover"));
            assertTrue(done.settled(), done.status() + " " + done.error());
        }
    }

    private static <T> T asUser(String userId, Supplier<T> action) {
        return UserContext.call(userId, () -> SubmitCredentialCapture.call("tok-" + userId, action));
    }

    private static boolean hasPending(ChatSessions sessions, String userId, String sessionId) {
        try {
            ThreadView.SnapshotResponse view = asUser(userId, () -> sessions.view(sessionId));
            return view != null
                    && view.pendingInteractions() != null
                    && !view.pendingInteractions().isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String pendingRequestId(ChatSessions sessions, String userId, String sessionId) {
        ThreadView.SnapshotResponse view = asUser(userId, () -> sessions.view(sessionId));
        assertNotNull(view);
        assertFalse(view.pendingInteractions().isEmpty());
        return view.pendingInteractions().getFirst().requestId();
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < limit) {
            try {
                if (condition.getAsBoolean()) return;
            } catch (RuntimeException ignored) {
                // view 竞态等可重试
            }
            Thread.sleep(40);
        }
        throw new AssertionError("等待超时");
    }

    private static GovernanceSnapshot snap(String id, String name, String uri) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("name", Map.of("type", "string")));
        schema.put("required", List.of("name"));
        return new GovernanceSnapshot(
                "tenant-a",
                "default",
                "agent-reader",
                id,
                List.of(new GovernanceToolView(uri, name, name, schema, "1:1:" + name + "@1", "1", "1", "read")));
    }

    private static final class MutableGateway implements GovernanceGateway {
        private final AtomicReference<GovernanceSnapshot> current = new AtomicReference<>();
        private final Map<String, GovernanceSnapshot> history = new LinkedHashMap<>();
        final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();

        MutableGateway(GovernanceSnapshot initial) {
            publish(initial);
        }

        void publish(GovernanceSnapshot snapshot) {
            current.set(snapshot);
            history.put(snapshot.snapshotId(), snapshot);
        }

        @Override
        public GovernanceSnapshot currentSnapshot() {
            return current.get();
        }

        @Override
        public GovernanceSnapshotFetch fetchCurrent(String ifNoneMatch) {
            GovernanceSnapshot snap = current.get();
            String etag = snap.snapshotId();
            if (ifNoneMatch != null && etag.equals(strip(ifNoneMatch))) {
                return GovernanceSnapshotFetch.notModified(etag);
            }
            return GovernanceSnapshotFetch.of(snap, etag);
        }

        @Override
        public GovernanceSnapshot fetchSnapshot(String snapshotId) {
            GovernanceSnapshot hit = history.get(snapshotId);
            if (hit == null) throw new IllegalStateException("missing snapshot " + snapshotId);
            return hit;
        }

        @Override
        public GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments) {
            calls.add(new Call(uri, snapshotId, Map.copyOf(arguments)));
            return GovernanceInvokeResult.completed(
                    "OK",
                    "ok",
                    Map.of("content", List.of(Map.of("type", "text", "text", "{\"ok\":true}")), "isError", false));
        }

        @Override
        public String fetchInstructions(String snapshotId, String skillId) {
            return "";
        }

        private static String strip(String etag) {
            String v = etag.trim();
            if (v.startsWith("W/")) v = v.substring(2).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            return v;
        }

        private record Call(String uri, String snapshotId, Map<String, Object> arguments) {}
    }

    private static final class HoldModel implements Model {
        volatile CountDownLatch blockUntil;
        volatile Runnable onStream;

        @Override
        public String getModelName() {
            return "hold";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            Runnable hook = onStream;
            if (hook != null) hook.run();
            CountDownLatch gate = blockUntil;
            if (gate != null) {
                try {
                    gate.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Flux.just(ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(TextBlock.builder().text("ok").build()))
                    .finishReason("stop")
                    .build());
        }
    }

    private static final class ScriptModel implements Model {
        volatile String nextTool;

        ScriptModel(String nextTool) {
            this.nextTool = nextTool;
        }

        @Override
        public String getModelName() {
            return "script";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean saw = messages.stream()
                    .anyMatch(m -> m.hasContentBlocks(ToolUseBlock.class) || m.hasContentBlocks(ToolResultBlock.class));
            ContentBlock content;
            String finish;
            if (!saw && nextTool != null) {
                Map<String, Object> args = Map.of("name", "x");
                content = ToolUseBlock.builder()
                        .id("t-" + UUID.randomUUID())
                        .name(nextTool)
                        .input(args)
                        .content(JsonUtils.getJsonCodec().toJson(args))
                        .build();
                finish = "tool_calls";
            } else {
                content = TextBlock.builder().text("done").build();
                finish = "stop";
            }
            return Flux.just(ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(content))
                    .finishReason(finish)
                    .build());
        }
    }

    private static final class ApprovalModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "ask";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean saw = messages.stream()
                    .anyMatch(m -> m.hasContentBlocks(ToolUseBlock.class) || m.hasContentBlocks(ToolResultBlock.class));
            ContentBlock content;
            String finish;
            if (!saw) {
                Map<String, Object> args = Map.of("path", "notes/x.md", "content", "x");
                content = ToolUseBlock.builder()
                        .id("a-" + calls.incrementAndGet())
                        .name("write_note")
                        .input(args)
                        .content(JsonUtils.getJsonCodec().toJson(args))
                        .build();
                finish = "tool_calls";
            } else {
                content = TextBlock.builder().text("done").build();
                finish = "stop";
            }
            return Flux.just(ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(content))
                    .finishReason(finish)
                    .build());
        }
    }

    private static final class PhasedHitlModel implements Model {
        final AtomicReference<String> phase = new AtomicReference<>("ask");
        volatile List<ToolSchema> lastTools = List.of();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "phased-hitl";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            lastTools = tools == null ? List.of() : List.copyOf(tools);
            boolean sawTool = messages.stream()
                    .anyMatch(m -> m.hasContentBlocks(ToolUseBlock.class) || m.hasContentBlocks(ToolResultBlock.class));
            boolean sawResult = messages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
            ContentBlock content;
            String finish;
            if ("ask".equals(phase.get()) && !sawTool) {
                Map<String, Object> args = Map.of("path", "notes/x.md", "content", "x");
                content = ToolUseBlock.builder()
                        .id("ask-" + calls.incrementAndGet())
                        .name("write_note")
                        .input(args)
                        .content(JsonUtils.getJsonCodec().toJson(args))
                        .build();
                finish = "tool_calls";
            } else if ("invoke_v1".equals(phase.get()) && sawResult && !hasNamedTool(messages, "tool_v1")) {
                Map<String, Object> args = Map.of("name", "x");
                content = ToolUseBlock.builder()
                        .id("v1-" + calls.incrementAndGet())
                        .name("tool_v1")
                        .input(args)
                        .content(JsonUtils.getJsonCodec().toJson(args))
                        .build();
                finish = "tool_calls";
            } else {
                content = TextBlock.builder().text("ok").build();
                finish = "stop";
            }
            return Flux.just(ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(content))
                    .finishReason(finish)
                    .build());
        }

        private static boolean hasNamedTool(List<Msg> messages, String name) {
            return messages.stream()
                    .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                    .anyMatch(b -> name.equals(b.getName()));
        }
    }
}
