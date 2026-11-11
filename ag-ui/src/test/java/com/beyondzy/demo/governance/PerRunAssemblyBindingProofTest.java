package com.beyondzy.demo.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.session.ChatSessions;
import com.beyondzy.demo.tools.DocSearchTools;
import com.beyondzy.demo.tools.NoteTools;
import com.beyondzy.demo.tools.OrderTools;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.SessionAnswer;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Phase 1 按 run 绑定：用户 A 待审批时，用户 B 新任务应绑定 v2；A resume 仍用 v1。
 * 同时覆盖刷新与提交的锁内串行，以及旧 Harness 引用归零前不得关闭。
 */
class PerRunAssemblyBindingProofTest {

    private static final String URI_V1 = "driver://mcp/tenant-a/demo/tool_v1";
    private static final String URI_V2 = "driver://mcp/tenant-a/demo/tool_v2";
    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";

    @AfterEach
    void clear() {
        RunCredentialRegistry.clearAll();
    }

    @Test
    void userBGetsV2WhileUserAApprovalStillOnV1(@TempDir Path workspace) throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        WorkspaceSessionLogStore store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        DualUserModel model = new DualUserModel();
        Object lock = new Object();

        PreparedGovernanceAssembly v1 = prepare(workspace, gateway, "snap_v1", "tool_v1", URI_V1, "v1-skills");
        HarnessGenerationPool pool = new HarnessGenerationPool(
                assembly -> harness(workspace, store, assembly, model), lock);
        pool.bootstrap(v1);

        String keyA = USER_A + "\0sess-a";
        HarnessGenerationPool.Generation genA = pool.bindForSubmit(keyA, (g, k) -> false);
        pool.confirmEnqueued(keyA);
        AgentSession sessionA = genA.session(ctx(USER_A, "sess-a"));
        sessionA.start();
        model.phase.set("ask");
        AgentSession.Task pendingA = sessionA.submit("cmd-a", "请记录审批笔记");
        waitUntil(() -> !sessionA.pending().isEmpty());
        assertFalse(pendingA.settled());
        assertEquals("snap_v1", genA.snapshotId());
        assertToolSchemasContain(model.lastTools.get(), "tool_v1");

        // publish v2：新装配可供新 run，不关闭仍被 A 占用的 v1
        PreparedGovernanceAssembly v2 = prepare(workspace, gateway, "snap_v2", "tool_v2", URI_V2, "v2-skills");
        assertTrue(pool.adopt(v2));
        assertEquals("snap_v2", pool.currentSnapshotId());
        assertFalse(genA.closed(), "A 未收口时不得关闭 v1 Harness");

        String keyB = USER_B + "\0sess-b";
        model.phase.set("invoke_v2");
        HarnessGenerationPool.Generation genB = pool.bindForSubmit(keyB, (g, k) -> false);
        pool.confirmEnqueued(keyB);
        assertEquals("snap_v2", genB.snapshotId());
        AgentSession sessionB = genB.session(ctx(USER_B, "sess-b"));
        sessionB.start();
        RunCredentialRegistry.bindCommand("cmd-b", USER_B, "sess-b", "tok-b");
        AgentSession.Task submittedB = sessionB.submit("cmd-b", "查一下");
        RunCredentialLifecycle.arm(sessionB, USER_B, "sess-b", submittedB);
        AgentSession.Task doneB = sessionB.await(submittedB).block(Duration.ofSeconds(20));
        assertNotNull(doneB);
        assertTrue(doneB.settled(), doneB.status() + " " + doneB.error());
        assertToolSchemasContain(model.lastTools.get(), "tool_v2");
        assertFalse(
                model.lastTools.get().stream().anyMatch(t -> "tool_v1".equals(t.getName())),
                "B 不应再看到 v1 工具");
        assertEquals(1, gateway.calls.size());
        assertEquals("snap_v2", gateway.calls.get(0).snapshotId());
        assertEquals(URI_V2, gateway.calls.get(0).uri());

        // A resume 仍走 v1 一代
        model.phase.set("chat");
        String requestId = sessionA.pending().keySet().iterator().next();
        sessionA.respond(requestId, SessionAnswer.reject("keep-v1"));
        AgentSession.Task finishedA = sessionA.await(pendingA).block(Duration.ofSeconds(20));
        assertNotNull(finishedA);
        assertTrue(finishedA.settled());
        assertEquals("snap_v1", genA.snapshotId());
        assertTrue(gateway.calls.stream().filter(c -> URI_V1.equals(c.uri())).findAny().isEmpty()
                || gateway.calls.stream()
                        .filter(c -> URI_V1.equals(c.uri()))
                        .allMatch(c -> "snap_v1".equals(c.snapshotId())));

        pool.releaseSession(keyA);
        pool.releaseSession(keyB);
        assertTrue(genA.closed() || genA.retainCount() == 0);
        sessionA.close();
        sessionB.close();
        pool.close();
    }

    @Test
    void refreshAndSubmitSerializeSoTaskBindsOneCompleteAssembly(@TempDir Path workspace) throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        WorkspaceSessionLogStore store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        ChatModel model = new ChatModel();
        Object lock = new Object();
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch submitMayProceed = new CountDownLatch(1);
        AtomicBoolean refreshHoldsLock = new AtomicBoolean(false);
        AtomicReference<String> boundSnapshot = new AtomicReference<>();

        PreparedGovernanceAssembly v1 = prepare(workspace, gateway, "snap_v1", "tool_v1", URI_V1, "v1");
        HarnessGenerationPool pool = new HarnessGenerationPool(
                assembly -> harness(workspace, store, assembly, model), lock);
        pool.bootstrap(v1);

        Thread refresher = new Thread(() -> {
            synchronized (lock) {
                refreshHoldsLock.set(true);
                refreshEntered.countDown();
                try {
                    assertTrue(submitMayProceed.await(5, TimeUnit.SECONDS));
                    PreparedGovernanceAssembly v2 =
                            prepare(workspace, gateway, "snap_v2", "tool_v2", URI_V2, "v2");
                    pool.adopt(v2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    refreshHoldsLock.set(false);
                }
            }
        }, "refresh-thread");
        refresher.start();
        assertTrue(refreshEntered.await(5, TimeUnit.SECONDS));

        Thread submitter = new Thread(() -> {
            // 与 adopt 共用 lock：若 refresh 持锁，这里必须等到 adopt 完成，才能 bind 到完整 v2
            synchronized (lock) {
                assertFalse(refreshHoldsLock.get(), "提交不应与未完成的 adopt 交错进入临界区");
            }
            HarnessGenerationPool.Generation gen =
                    pool.bindForSubmit("user-x\0sess-x", (g, k) -> false);
            pool.confirmEnqueued("user-x\0sess-x");
            boundSnapshot.set(gen.snapshotId());
        }, "submit-thread");
        submitter.start();
        Thread.sleep(100);
        submitMayProceed.countDown();
        refresher.join(Duration.ofSeconds(5));
        submitter.join(Duration.ofSeconds(5));
        assertEquals("snap_v2", boundSnapshot.get());
        pool.close();
    }

    @Test
    void sameSessionQueuedTasksKeepBoundGenerationUntilSettled(@TempDir Path workspace) throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        WorkspaceSessionLogStore store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        ApprovalModel model = new ApprovalModel();
        Object lock = new Object();
        PreparedGovernanceAssembly v1 = prepare(workspace, gateway, "snap_v1", "tool_v1", URI_V1, "v1");
        HarnessGenerationPool pool = new HarnessGenerationPool(
                assembly -> harness(workspace, store, assembly, model), lock);
        pool.bootstrap(v1);

        String key = USER_A + "\0sess-serial";
        HarnessGenerationPool.Generation gen1 = pool.bindForSubmit(key, (g, k) -> false);
        pool.confirmEnqueued(key);
        AgentSession session = gen1.session(ctx(USER_A, "sess-serial"));
        session.start();
        AgentSession.Task pending = session.submit("cmd-1", "请记录审批笔记");
        waitUntil(() -> !session.pending().isEmpty());

        PreparedGovernanceAssembly v2 = prepare(workspace, gateway, "snap_v2", "tool_v2", URI_V2, "v2");
        pool.adopt(v2);

        // 会话仍忙：后续 bind 必须仍落在 v1（提交时绑定 / 排队不跨代）
        HarnessGenerationPool.Generation genQueued = pool.bindForSubmit(key, (g, k) -> {
            AgentSession s = g.session(ctx(USER_A, "sess-serial"));
            return s.pending() != null && !s.pending().isEmpty();
        });
        assertEquals("snap_v1", genQueued.snapshotId());
        assertEquals(gen1, genQueued);
        pool.confirmEnqueued(key); // 模拟入队成功，解除 enqueue hold

        String requestId = session.pending().keySet().iterator().next();
        session.respond(requestId, SessionAnswer.reject("done"));
        assertTrue(session.await(pending).block(Duration.ofSeconds(20)).settled());

        pool.onTaskSettled(key, gen1, (g, k) -> false);
        HarnessGenerationPool.Generation genNext = pool.bindForSubmit(key, (g, k) -> false);
        pool.confirmEnqueued(key);
        assertEquals("snap_v2", genNext.snapshotId());
        session.close();
        pool.close();
    }

    private static void assertToolSchemasContain(List<ToolSchema> tools, String name) {
        assertNotNull(tools);
        Set<String> names = tools.stream().map(ToolSchema::getName).collect(Collectors.toSet());
        assertTrue(names.contains(name), "tools=" + names);
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < limit) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("等待超时");
    }

    private static RuntimeContext ctx(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).runId("run-" + sessionId).build();
    }

    private static PreparedGovernanceAssembly prepare(
            Path workspace, GovernanceGateway gateway, String snap, String tool, String uri, String skill) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        toolkit.registerTool(new DocSearchTools());
        toolkit.registerTool(new NoteTools(workspace));
        GovernanceSnapshot snapshot = snapshot(snap, tool, uri);
        GovernanceToolMount mount = new GovernanceToolMount(toolkit, gateway);
        mount.install(snapshot);
        return new PreparedGovernanceAssembly(
                snapshot, toolkit, mount, "\n## Governance skills\n" + skill + "\n", snap);
    }

    private static HarnessAgent harness(
            Path workspace, WorkspaceSessionLogStore store, PreparedGovernanceAssembly assembly, Model model) {
        Toolkit toolkit = assembly == null ? new Toolkit() : assembly.toolkit();
        String skills = assembly == null || assembly.skillsSection() == null ? "" : assembly.skillsSection();
        return HarnessAgent.builder()
                .name("Session Chat")
                .agentId("session-chat")
                .model(model)
                .sysPrompt(ChatSessions.systemPrompt(assembly != null, skills))
                .workspace(workspace)
                .sessionLogStore(store)
                .toolkit(toolkit)
                .permissionContext(PermissionContextState.builder()
                        .addAllowRule("tool_v1", new PermissionRule("tool_v1", null, PermissionBehavior.ALLOW, "ro"))
                        .addAllowRule("tool_v2", new PermissionRule("tool_v2", null, PermissionBehavior.ALLOW, "ro"))
                        .addAskRule("write_note", new PermissionRule("write_note", null, PermissionBehavior.ASK, "ask"))
                        .build())
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableToolsConfig()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .maxIters(4)
                .build();
    }

    private static GovernanceSnapshot snapshot(String id, String name, String uri) {
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

    private static final class RecordingGateway implements GovernanceGateway {
        final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public GovernanceSnapshot currentSnapshot() {
            throw new UnsupportedOperationException();
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

        private record Call(String uri, String snapshotId, Map<String, Object> arguments) {}
    }

    private static final class DualUserModel implements Model {
        final AtomicReference<String> phase = new AtomicReference<>("ask");
        final AtomicReference<List<ToolSchema>> lastTools = new AtomicReference<>(List.of());
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "per-run-model";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            lastTools.set(tools == null ? List.of() : List.copyOf(tools));
            calls.incrementAndGet();
            boolean sawTool = messages.stream()
                    .anyMatch(m -> m.hasContentBlocks(ToolUseBlock.class) || m.hasContentBlocks(ToolResultBlock.class));
            ContentBlock content;
            String finish;
            if ("ask".equals(phase.get()) && !sawTool) {
                Map<String, Object> args = Map.of("path", "notes/审批记录.md", "content", "审批中");
                content = ToolUseBlock.builder()
                        .id("approval-" + calls.get())
                        .name("write_note")
                        .input(args)
                        .content(JsonUtils.getJsonCodec().toJson(args))
                        .build();
                finish = "tool_calls";
            } else if ("invoke_v2".equals(phase.get()) && !sawTool) {
                Map<String, Object> args = Map.of("name", "x");
                content = ToolUseBlock.builder()
                        .id("tool-" + calls.get())
                        .name("tool_v2")
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
    }

    private static final class ChatModel implements Model {
        @Override
        public String getModelName() {
            return "chat";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(TextBlock.builder().text("hi").build()))
                    .finishReason("stop")
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
            boolean sawTool = messages.stream()
                    .anyMatch(m -> m.hasContentBlocks(ToolUseBlock.class) || m.hasContentBlocks(ToolResultBlock.class));
            ContentBlock content;
            String finish;
            if (!sawTool) {
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
}
