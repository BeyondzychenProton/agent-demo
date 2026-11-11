package com.beyondzy.demo.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.tools.DocSearchTools;
import com.beyondzy.demo.tools.NoteTools;
import com.beyondzy.demo.tools.OrderTools;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Execution-path regressions for Run credential lifecycle (no SSE / pollCommand).
 *
 * <p>Covers: handoff without observation, auto-clear after settle, fail/cancel cleanup,
 * multi-run isolation, fail-closed without credential.
 */
class RunCredentialLifecycleTest {

    @AfterEach
    void clearScopes() {
        RunCredentialRegistry.clearAll();
        OutboundUserCredential.clear();
    }

    @Test
    void noSseObservationStillInvokesWithRunCredential(@TempDir Path workspace) throws Exception {
        RecordingGateway gateway = recordingGateway();
        Toolkit toolkit = toolkitWithIdentity(workspace, gateway);
        RuntimeContext ctx = context("user-obs", "thread-obs");
        Model model = new ScriptModel(messages -> sawTool(messages)
                ? text("done")
                : toolCall("identity_resolve", Map.of("name", "demo")));

        try (HarnessAgent agent = harness(workspace, toolkit, model)) {
            AgentSession session = agent.session(ctx);
            session.start();
            String secret = "token-no-sse-" + UUID.randomUUID();
            String commandId = "cmd-no-sse";
            RunCredentialRegistry.bindCommand(commandId, ctx.getUserId(), ctx.getSessionId(), secret);
            AgentSession.Task submitted = session.submit(commandId, "query demo");
            // No SSE / no manual transfer; lifecycle arm only (same as ChatSessions).
            RunCredentialLifecycle.arm(session, ctx.getUserId(), ctx.getSessionId(), submitted);

            waitFor(() -> !gateway.calls.isEmpty() || settled(session, commandId), Duration.ofSeconds(20));
            assertFalse(gateway.calls.isEmpty(), "tool must obtain Run credential without SSE");
            AgentSession.Task finished = session.await(submitted).block(Duration.ofSeconds(20));
            assertTrue(finished != null && finished.settled(), finished == null ? "null" : finished.status());
            waitFor(
                    () -> RunCredentialRegistry.resolve(runContext(ctx, finished.runId())).isEmpty()
                            && RunCredentialRegistry.findByCommand(commandId).isEmpty(),
                    Duration.ofSeconds(5));
            assertEquals(1, gateway.calls.size());
            session.close();
        }
    }

    @Test
    void settleWithoutSseAutoClearsRunCredential(@TempDir Path workspace) throws Exception {
        RecordingGateway gateway = recordingGateway();
        Toolkit toolkit = toolkitWithIdentity(workspace, gateway);
        RuntimeContext ctx = context("user-clear", "thread-clear");
        Model model = new ScriptModel(messages -> sawTool(messages)
                ? text("done")
                : toolCall("identity_resolve", Map.of("name", "demo")));

        try (HarnessAgent agent = harness(workspace, toolkit, model)) {
            AgentSession session = agent.session(ctx);
            session.start();
            String commandId = "cmd-clear";
            RunCredentialRegistry.bindCommand(
                    commandId, ctx.getUserId(), ctx.getSessionId(), "token-clear-" + UUID.randomUUID());
            AgentSession.Task submitted = session.submit(commandId, "query");
            RunCredentialLifecycle.arm(session, ctx.getUserId(), ctx.getSessionId(), submitted);
            AgentSession.Task done = session.await(submitted).block(Duration.ofSeconds(20));
            assertTrue(done != null && done.settled(), done == null ? "null" : done.status());
            assertEquals(1, gateway.calls.size());
            waitFor(
                    () -> RunCredentialRegistry.findByCommand(commandId).isEmpty()
                            && (done.runId() == null
                                    || RunCredentialRegistry.resolve(runContext(ctx, done.runId())).isEmpty()),
                    Duration.ofSeconds(5));
            session.close();
        }
    }

    @Test
    void failedRunClearsCredential(@TempDir Path workspace) throws Exception {
        Toolkit toolkit = toolkitWithIdentity(workspace, recordingGateway());
        RuntimeContext ctx = context("user-fail", "thread-fail");
        Model model = new ScriptModel(messages -> {
            throw new IllegalStateException("scripted-model-failure");
        });

        try (HarnessAgent agent = harness(workspace, toolkit, model)) {
            AgentSession session = agent.session(ctx);
            session.start();
            String commandId = "cmd-fail";
            RunCredentialRegistry.bindCommand(
                    commandId, ctx.getUserId(), ctx.getSessionId(), "token-fail-" + UUID.randomUUID());
            AgentSession.Task submitted = session.submit(commandId, "will fail");
            RunCredentialLifecycle.arm(session, ctx.getUserId(), ctx.getSessionId(), submitted);
            AgentSession.Task done = session.await(submitted).block(Duration.ofSeconds(20));
            assertTrue(done != null && done.settled(), done == null ? "null" : done.status());
            waitFor(
                    () -> RunCredentialRegistry.findByCommand(commandId).isEmpty()
                            && (done.runId() == null
                                    || RunCredentialRegistry.resolve(runContext(ctx, done.runId())).isEmpty()),
                    Duration.ofSeconds(5));
            session.close();
        }
    }

    @Test
    void cancelClearsCredential(@TempDir Path workspace) throws Exception {
        Toolkit toolkit = toolkitWithIdentity(workspace, recordingGateway());
        RuntimeContext ctx = context("user-cancel", "thread-cancel");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Model model = new ScriptModel(messages -> {
            entered.countDown();
            awaitQuiet(release);
            return text("after-cancel");
        });

        try (HarnessAgent agent = harness(workspace, toolkit, model)) {
            AgentSession session = agent.session(ctx);
            session.start();
            String commandId = "cmd-cancel";
            RunCredentialRegistry.bindCommand(
                    commandId, ctx.getUserId(), ctx.getSessionId(), "token-cancel-" + UUID.randomUUID());
            AgentSession.Task submitted = session.submit(commandId, "long task");
            RunCredentialLifecycle.arm(session, ctx.getUserId(), ctx.getSessionId(), submitted);
            assertTrue(entered.await(15, TimeUnit.SECONDS), "model should enter execution");
            String runId = waitForRunId(session, commandId, Duration.ofSeconds(5));
            assertTrue(session.interrupt(runId) || session.interrupt(), "interrupt should succeed");
            release.countDown();
            AgentSession.Task done = session.await(submitted).block(Duration.ofSeconds(20));
            assertTrue(done != null && done.settled(), done == null ? "null" : done.status());
            waitFor(
                    () -> RunCredentialRegistry.findByCommand(commandId).isEmpty()
                            && (runId == null
                                    || RunCredentialRegistry.resolve(runContext(ctx, runId)).isEmpty()),
                    Duration.ofSeconds(5));
            session.close();
        }
    }

    @Test
    void rejectedOrMissingCommandLifecycleClearsWithoutManualClear(@TempDir Path workspace) throws Exception {
        // Unknown commandId: session.task fails; lifecycle must fail-closed clear command.
        try (HarnessAgent agent = harness(
                workspace,
                toolkitWithIdentity(workspace, recordingGateway()),
                new ScriptModel(messages -> text("x")))) {
            RuntimeContext ctx = context("user-rej", "thread-rej");
            AgentSession session = agent.session(ctx);
            session.start();
            String commandId = "cmd-never-accepted";
            RunCredentialRegistry.bindCommand(commandId, ctx.getUserId(), ctx.getSessionId(), "token-rej");
            assertTrue(RunCredentialRegistry.findByCommand(commandId).isPresent());
            RunCredentialLifecycle.watch(session, ctx.getUserId(), ctx.getSessionId(), commandId);
            waitFor(() -> RunCredentialRegistry.findByCommand(commandId).isEmpty(), Duration.ofSeconds(5));
            session.close();
        }
    }

    @Test
    void concurrentRunsKeepIsolatedCredentialsAndClearIndependently(@TempDir Path workspace) throws Exception {
        RecordingGateway gateway = recordingGateway();
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bEntered = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        gateway.onInvoke = args -> {
            String name = String.valueOf(args.get("name"));
            if ("A".equals(name)) {
                aEntered.countDown();
                awaitQuiet(releaseA);
            } else {
                bEntered.countDown();
                awaitQuiet(releaseB);
            }
        };
        RuntimeContext ctxA = context("1", "chat-a");
        RuntimeContext ctxB = context("2", "chat-b");
        Model modelA = new ScriptModel(messages -> sawTool(messages)
                ? text("ok-a")
                : toolCall("identity_resolve", Map.of("name", "A")));
        Model modelB = new ScriptModel(messages -> sawTool(messages)
                ? text("ok-b")
                : toolCall("identity_resolve", Map.of("name", "B")));

        Path wsA = workspace.resolve("a");
        Path wsB = workspace.resolve("b");
        try (HarnessAgent agentA = harness(wsA, toolkitWithIdentity(wsA, gateway), modelA);
                HarnessAgent agentB = harness(wsB, toolkitWithIdentity(wsB, gateway), modelB)) {
            AgentSession sessionA = agentA.session(ctxA);
            AgentSession sessionB = agentB.session(ctxB);
            sessionA.start();
            sessionB.start();
            RunCredentialRegistry.bindCommand("cmd-a", "1", "chat-a", "token-a");
            RunCredentialRegistry.bindCommand("cmd-b", "2", "chat-b", "token-b");
            AgentSession.Task taskA = sessionA.submit("cmd-a", "query A");
            AgentSession.Task taskB = sessionB.submit("cmd-b", "query B");
            RunCredentialLifecycle.arm(sessionA, "1", "chat-a", taskA);
            RunCredentialLifecycle.arm(sessionB, "2", "chat-b", taskB);
            assertTrue(aEntered.await(20, TimeUnit.SECONDS));
            assertTrue(bEntered.await(20, TimeUnit.SECONDS));
            String runA = waitForRunId(sessionA, "cmd-a", Duration.ofSeconds(5));
            String runB = waitForRunId(sessionB, "cmd-b", Duration.ofSeconds(5));
            assertTrue(RunCredentialRegistry.hasRunBinding("1", "chat-a", runA)
                    || RunCredentialRegistry.resolve(runContext(ctxA, runA)).isPresent());
            assertTrue(RunCredentialRegistry.hasRunBinding("2", "chat-b", runB)
                    || RunCredentialRegistry.resolve(runContext(ctxB, runB)).isPresent());

            releaseA.countDown();
            AgentSession.Task doneA = sessionA.await(taskA).block(Duration.ofSeconds(20));
            assertTrue(doneA != null && doneA.settled());
            waitFor(
                    () -> RunCredentialRegistry.findByCommand("cmd-a").isEmpty()
                            && RunCredentialRegistry.resolve(runContext(ctxA, runA)).isEmpty(),
                    Duration.ofSeconds(5));
            assertTrue(
                    RunCredentialRegistry.hasRunBinding("2", "chat-b", runB)
                            || RunCredentialRegistry.resolve(runContext(ctxB, runB)).isPresent(),
                    "clearing A must not affect active B");

            releaseB.countDown();
            AgentSession.Task doneB = sessionB.await(taskB).block(Duration.ofSeconds(20));
            assertTrue(doneB != null && doneB.settled());
            waitFor(
                    () -> RunCredentialRegistry.findByCommand("cmd-b").isEmpty()
                            && RunCredentialRegistry.resolve(runContext(ctxB, runB)).isEmpty(),
                    Duration.ofSeconds(5));
            sessionA.close();
            sessionB.close();
        }
    }

    @Test
    void missingCredentialFailsClosedOnRealToolPath(@TempDir Path workspace) {
        RecordingGateway gateway = recordingGateway();
        Toolkit toolkit = toolkitWithIdentity(workspace, gateway);
        RuntimeContext ctx = runContext(context("u", "s"), "run-x");
        ToolResultBlock result = toolkit.callTool(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("no-cred")
                                .name("identity_resolve")
                                .input(Map.of("name", "x"))
                                .content("{\"name\":\"x\"}")
                                .build())
                        .input(Map.of("name", "x"))
                        .runtimeContext(ctx)
                        .build())
                .block(Duration.ofSeconds(5));
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(0, gateway.calls.size());
        assertTrue(text(result).contains("UNAUTHENTICATED") || text(result).contains("credential")
                || text(result).contains("\u51ed\u8bc1"));
    }

    @Test
    void ensureRunBindingRequiresHarnessConfirmedAssociation() {
        RuntimeContext ctx = runContext(context("u1", "s1"), "run-1");
        RunCredentialRegistry.bindCommand("cmd-1", "u1", "s1", "token-1");
        // 无 Harness 关联时不得猜测移交
        assertFalse(RunCredentialRegistry.ensureRunBinding(ctx));
        assertTrue(RunCredentialRegistry.resolve(ctx).isEmpty());
        assertTrue(RunCredentialRegistry.findByCommand("cmd-1", "u1", "s1").isPresent());

        assertTrue(RunCredentialRegistry.confirmAssociation("cmd-1", "u1", "s1", "run-1"));
        assertTrue(RunCredentialRegistry.ensureRunBinding(ctx));
        assertEquals("token-1", RunCredentialRegistry.resolve(ctx).orElseThrow());
        assertTrue(RunCredentialRegistry.findByCommand("cmd-1", "u1", "s1").isEmpty());
        assertFalse(RunCredentialRegistry.ensureRunBinding(runContext(context("u1", "s1"), "run-other")));
        assertTrue(RunCredentialRegistry.resolve(runContext(context("u1", "s1"), "run-other")).isEmpty());
    }

    @Test
    void restoredOldRunCannotBorrowQueuedCommandCredential() {
        RuntimeContext oldRun = runContext(context("u1", "s1"), "run-old");
        RunCredentialRegistry.bindCommand("cmd-new", "u1", "s1", "new-login-token");
        // 旧 Run 无关联、无绑定：不得借走新排队 command
        assertFalse(RunCredentialRegistry.ensureRunBinding(oldRun));
        assertTrue(RunCredentialRegistry.resolve(oldRun).isEmpty());
        assertEquals(
                "new-login-token",
                RunCredentialRegistry.findByCommand("cmd-new", "u1", "s1").orElseThrow());

        // 仅当 Harness 确认 cmd-new→run-new 时，新 Run 才能取得凭证
        RuntimeContext newRun = runContext(context("u1", "s1"), "run-new");
        assertTrue(RunCredentialRegistry.confirmAssociation("cmd-new", "u1", "s1", "run-new"));
        assertTrue(RunCredentialRegistry.ensureRunBinding(newRun));
        assertEquals("new-login-token", RunCredentialRegistry.resolve(newRun).orElseThrow());
        assertTrue(RunCredentialRegistry.resolve(oldRun).isEmpty());
    }

    @Test
    void productionSubmitOrderBindsBeforeHarnessExecute(@TempDir Path workspace) throws Exception {
        RecordingGateway gateway = recordingGateway();
        Toolkit toolkit = toolkitWithIdentity(workspace, gateway);
        RuntimeContext ctx = context("user-order", "thread-order");
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        gateway.onInvoke = args -> {
            toolEntered.countDown();
            awaitQuiet(releaseTool);
        };
        Model model = new ScriptModel(messages -> sawTool(messages)
                ? text("done")
                : toolCall("identity_resolve", Map.of("name", "order")));

        try (HarnessAgent agent = harness(workspace, toolkit, model)) {
            AgentSession session = agent.session(ctx);
            session.start();
            String commandId = "cmd-prod-order";
            String secret = "token-prod-order-" + UUID.randomUUID();
            // 生产路径：先 bind，再 submit，再 arm（与 ChatSessions 一致）
            RunCredentialRegistry.bindCommand(commandId, ctx.getUserId(), ctx.getSessionId(), secret);
            AgentSession.Task submitted = session.submit(commandId, "query");
            RunCredentialLifecycle.arm(session, ctx.getUserId(), ctx.getSessionId(), submitted);
            assertTrue(toolEntered.await(20, TimeUnit.SECONDS), "tool should run with bound credential");
            releaseTool.countDown();
            AgentSession.Task done = session.await(submitted).block(Duration.ofSeconds(20));
            assertTrue(done != null && done.settled(), done == null ? "null" : done.status());
            assertEquals(1, gateway.calls.size());
            waitFor(
                    () -> RunCredentialRegistry.findByCommand(commandId, ctx.getUserId(), ctx.getSessionId())
                                    .isEmpty()
                            && (done.runId() == null
                                    || RunCredentialRegistry.resolve(runContext(ctx, done.runId())).isEmpty()),
                    Duration.ofSeconds(5));
            session.close();
        }
    }

    @Test
    void processRestartFailsClosedWithoutRestoringToken(@TempDir Path workspace) {
        Toolkit toolkit = toolkitWithIdentity(workspace, recordingGateway());
        RuntimeContext ctx = runContext(context("user-restart", "thread-restart"), "run-restart");
        RunCredentialRegistry.bindRun(
                "user-restart", "thread-restart", "run-restart", "token-before-restart");
        assertTrue(RunCredentialRegistry.resolve(ctx).isPresent());
        RunCredentialRegistry.clearAll();
        assertTrue(RunCredentialRegistry.resolve(ctx).isEmpty());
        ToolResultBlock missing = toolkit.callTool(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("restart-1")
                                .name("identity_resolve")
                                .input(Map.of("name", "x"))
                                .content("{\"name\":\"x\"}")
                                .build())
                        .input(Map.of("name", "x"))
                        .runtimeContext(ctx)
                        .build())
                .block(Duration.ofSeconds(5));
        assertEquals(ToolResultState.ERROR, missing.getState());
    }

    private static void awaitQuiet(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean settled(AgentSession session, String commandId) {
        AgentSession.Task task = session.task(commandId);
        return task != null && task.settled();
    }

    private static String waitForRunId(AgentSession session, String commandId, Duration limit)
            throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            AgentSession.Task task = session.task(commandId);
            if (task != null && task.runId() != null && !task.runId().isBlank()) return task.runId();
            if (task != null && task.settled()) return task.runId();
            Thread.sleep(20);
        }
        throw new AssertionError("timeout waiting for runId");
    }

    private static void waitFor(BooleanSupplier condition, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("timeout waiting for condition");
    }

    private static RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static RuntimeContext runContext(RuntimeContext base, String runId) {
        return RuntimeContext.builder()
                .userId(base.getUserId())
                .sessionId(base.getSessionId())
                .runId(runId)
                .build();
    }

    private static RecordingGateway recordingGateway() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.response = GovernanceInvokeResult.completed(
                "OK",
                "ok",
                Map.of(
                        "content",
                        List.of(Map.of("type", "text", "text", "{\"resolutionStatus\":\"RESOLVED\"}")),
                        "isError",
                        false));
        return gateway;
    }

    private static Toolkit toolkitWithIdentity(Path workspace, GovernanceGateway gateway) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        toolkit.registerTool(new DocSearchTools());
        toolkit.registerTool(new NoteTools(workspace));
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        schema.put("additionalProperties", Boolean.FALSE);
        GovernanceToolView tool = new GovernanceToolView(
                "driver://mcp/tenant-a/cms-identity-invoke/identity.resolve",
                "identity.resolve",
                "identity lookup",
                schema,
                "1:1:identity.resolve@1",
                "9",
                "3",
                "read");
        GovernanceSnapshot snapshot =
                new GovernanceSnapshot("tenant-a", "default", "agent-invoke-demo", "snap_life", List.of(tool));
        new GovernanceToolMount(toolkit, gateway).install(snapshot);
        return toolkit;
    }

    private static HarnessAgent harness(Path workspace, Toolkit toolkit, Model model) {
        return HarnessAgent.builder()
                .name("Session Chat")
                .agentId("session-chat")
                .model(model)
                .sysPrompt(com.beyondzy.demo.session.ChatSessions.systemPrompt(true))
                .workspace(workspace)
                .sessionLogStore(new WorkspaceSessionLogStore(new LocalFilesystem(workspace)))
                .toolkit(toolkit)
                .permissionContext(PermissionContextState.builder()
                        .addAllowRule(
                                "lookup_order",
                                new PermissionRule("lookup_order", null, PermissionBehavior.ALLOW, "allow"))
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

    private static boolean sawTool(List<Msg> messages) {
        return messages.stream().anyMatch(message ->
                message.getRole() == MsgRole.TOOL || message.hasContentBlocks(ToolResultBlock.class));
    }

    private static ContentBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private static ContentBlock toolCall(String name, Map<String, Object> args) {
        return ToolUseBlock.builder()
                .id("call-" + UUID.randomUUID())
                .name(name)
                .input(args)
                .content(JsonUtils.getJsonCodec().toJson(args))
                .build();
    }

    private static String text(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) return "";
        ContentBlock first = result.getOutput().get(0);
        if (first instanceof TextBlock textBlock) return textBlock.getText() == null ? "" : textBlock.getText();
        return String.valueOf(first);
    }

    private static final class RecordingGateway implements GovernanceGateway {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        GovernanceInvokeResult response;
        java.util.function.Consumer<Map<String, Object>> onInvoke = args -> {};

        @Override
        public GovernanceSnapshot currentSnapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments) {
            onInvoke.accept(arguments == null ? Map.of() : arguments);
            calls.add(new Call(uri, snapshotId, Map.copyOf(arguments)));
            return response;
        }

        @Override
        public String fetchInstructions(String snapshotId, String skillId) {
            return "";
        }

        private record Call(String uri, String snapshotId, Map<String, Object> arguments) {}
    }

    @FunctionalInterface
    private interface TurnScript {
        ContentBlock next(List<Msg> messages);
    }

    private static final class ScriptModel implements Model {
        private final TurnScript script;
        private final AtomicInteger calls = new AtomicInteger();

        ScriptModel(TurnScript script) {
            this.script = script;
        }

        @Override
        public String getModelName() {
            return "script-lifecycle";
        }

        @Override
        public Flux<io.agentscope.core.model.ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            ContentBlock content = script.next(messages);
            String finish = content instanceof ToolUseBlock ? "tool_calls" : "stop";
            return Flux.just(io.agentscope.core.model.ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(content))
                    .finishReason(finish)
                    .build());
        }
    }
}
