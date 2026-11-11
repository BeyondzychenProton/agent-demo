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
import io.agentscope.core.message.MsgRole;
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
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Phase 0 实证：同一会话连续 run 可分别绑定 Snapshot A/B；审批恢复仍使用原装配。
 *
 * <p>结论：HarnessAgent 在 build 时复制 Toolkit，无 live 换装 API；应按装配版本重建 Harness，
 * 共享同一 SessionLogStore。进行中的 AgentSession 必须继续挂在原 Harness 上 resume。
 */
class HarnessSnapshotBindingProofTest {

    private static final String URI_A = "driver://mcp/tenant-a/identity/resolve-a";
    private static final String URI_B = "driver://mcp/tenant-a/identity/resolve-b";
    private static final String SESSION = "binding-proof";
    private static final String USER = "local-user";

    @AfterEach
    void clearCredentials() {
        RunCredentialRegistry.clearAll();
    }

    @Test
    void sameSessionConsecutiveRunsBindSnapshotAThenB(@TempDir Path workspace) {
        RecordingGateway gateway = new RecordingGateway();
        WorkspaceSessionLogStore store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        SwitchingModel model = new SwitchingModel();

        Toolkit toolkitA = localToolkit(workspace);
        GovernanceToolMount mountA = new GovernanceToolMount(toolkitA, gateway);
        mountA.install(snapshot("snap_a", "tool_a", URI_A, "A版查询"));
        try (HarnessAgent agentA = harness(workspace, store, toolkitA, model, "技能A正文")) {
            AgentSession session = agentA.session(context());
            session.start();
            model.nextToolName = "tool_a";
            AgentSession.Task doneA = awaitWithCredential(session, "req-a", "查询A");
            assertTrue(doneA.settled(), doneA.status() + " " + doneA.error());
            assertEquals(1, gateway.calls.size());
            assertEquals("snap_a", gateway.calls.get(0).snapshotId());
            assertEquals(URI_A, gateway.calls.get(0).uri());
            assertEquals("snap_a", ((GovernanceBoundTool) agentA.getToolkit().getTool("tool_a")).snapshotId());
            session.close();
        }

        gateway.calls.clear();
        Toolkit toolkitB = localToolkit(workspace);
        GovernanceToolMount mountB = new GovernanceToolMount(toolkitB, gateway);
        mountB.install(snapshot("snap_b", "tool_b", URI_B, "B版查询"));
        try (HarnessAgent agentB = harness(workspace, store, toolkitB, model, "技能B正文")) {
            AgentSession session = agentB.session(context());
            session.start();
            assertTrue(transcript(session).contains("查询A"));
            assertEquals("snap_b", ((GovernanceBoundTool) agentB.getToolkit().getTool("tool_b")).snapshotId());
            assertTrue(agentB.getDelegate().getSysPrompt().contains("技能B正文"));
            model.nextToolName = "tool_b";
            AgentSession.Task doneB = awaitWithCredential(session, "req-b", "查询B");
            assertTrue(doneB.settled(), doneB.status() + " " + doneB.error());
            assertEquals(1, gateway.calls.size());
            assertEquals("snap_b", gateway.calls.get(0).snapshotId());
            assertEquals(URI_B, gateway.calls.get(0).uri());
            session.close();
        }
    }

    @Test
    void approvalResumeKeepsOriginalAssemblyWhileNewerReadyExists(@TempDir Path workspace) throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        WorkspaceSessionLogStore store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        ApprovalThenChatModel model = new ApprovalThenChatModel();

        Toolkit toolkitA = localToolkit(workspace);
        new GovernanceToolMount(toolkitA, gateway).install(snapshot("snap_a", "tool_a", URI_A, "A版查询"));
        HarnessAgent agentA = harness(workspace, store, toolkitA, model, "技能A正文");
        AgentSession sessionA = agentA.session(context());
        sessionA.start();
        AgentSession.Task pending = sessionA.submit("req-pending", "请记录审批笔记");
        waitUntil(() -> !sessionA.pending().isEmpty());
        assertFalse(pending.settled());
        assertEquals("snap_a", ((GovernanceBoundTool) agentA.getToolkit().getTool("tool_a")).snapshotId());

        // 模拟“最新成功装配”已切到 B：另建 Harness，不得拿它去 resume A。
        Toolkit toolkitB = localToolkit(workspace);
        new GovernanceToolMount(toolkitB, gateway).install(snapshot("snap_b", "tool_b", URI_B, "B版查询"));
        try (HarnessAgent agentB = harness(workspace, store, toolkitB, model, "技能B正文")) {
            assertEquals("snap_b", ((GovernanceBoundTool) agentB.getToolkit().getTool("tool_b")).snapshotId());
            assertEquals("snap_a", ((GovernanceBoundTool) agentA.getToolkit().getTool("tool_a")).snapshotId());
        }

        String requestId = sessionA.pending().keySet().iterator().next();
        sessionA.respond(requestId, io.agentscope.harness.agent.session.SessionAnswer.reject("keep-a"));
        AgentSession.Task finished = sessionA.await(pending).block(Duration.ofSeconds(20));
        assertNotNull(finished);
        assertTrue(finished.settled(), finished.status() + " " + finished.error());
        assertEquals("snap_a", ((GovernanceBoundTool) agentA.getToolkit().getTool("tool_a")).snapshotId());
        assertTrue(gateway.calls.isEmpty(), "审批拒绝写笔记不应触发治理 invoke");
        sessionA.close();
        agentA.close();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < limit) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("等待超时");
    }

    private static AgentSession.Task awaitWithCredential(AgentSession session, String commandId, String text) {
        RunCredentialRegistry.bindCommand(commandId, USER, SESSION, "proof-token");
        AgentSession.Task submitted = session.submit(commandId, text);
        RunCredentialLifecycle.arm(session, USER, SESSION, submitted);
        return session.await(submitted).block(Duration.ofSeconds(20));
    }

    private static String transcript(AgentSession session) {
        StringBuilder text = new StringBuilder();
        for (Msg message : session.transcript().messages()) {
            if (message.getTextContent() != null) text.append(message.getTextContent()).append('\n');
        }
        return text.toString();
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId(USER).sessionId(SESSION).runId("proof-run").build();
    }

    private static Toolkit localToolkit(Path workspace) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        toolkit.registerTool(new DocSearchTools());
        toolkit.registerTool(new NoteTools(workspace));
        return toolkit;
    }

    private static HarnessAgent harness(
            Path workspace, WorkspaceSessionLogStore store, Toolkit toolkit, Model model, String skillSection) {
        return HarnessAgent.builder()
                .name("Session Chat")
                .agentId("session-chat")
                .model(model)
                .sysPrompt(ChatSessions.systemPrompt(true, "\n## Governance skills\n" + skillSection + "\n"))
                .workspace(workspace)
                .sessionLogStore(store)
                .toolkit(toolkit)
                .permissionContext(PermissionContextState.builder()
                        .addAllowRule("lookup_order", new PermissionRule(
                                "lookup_order", null, PermissionBehavior.ALLOW, "平台查询策略"))
                        .addAllowRule("tool_a", new PermissionRule(
                                "tool_a", null, PermissionBehavior.ALLOW, "治理只读"))
                        .addAllowRule("tool_b", new PermissionRule(
                                "tool_b", null, PermissionBehavior.ALLOW, "治理只读"))
                        .addAskRule("write_note", new PermissionRule(
                                "write_note", null, PermissionBehavior.ASK, "平台审批策略"))
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

    private static GovernanceSnapshot snapshot(String snapshotId, String name, String uri, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("name", Map.of("type", "string")));
        schema.put("required", List.of("name"));
        return new GovernanceSnapshot(
                "tenant-a",
                "default",
                "agent-invoke-demo",
                snapshotId,
                List.of(new GovernanceToolView(
                        uri, name, description, schema, "1:1:" + name + "@1", "1", "1", "read")));
    }

    private static final class RecordingGateway implements GovernanceGateway {
        private final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public GovernanceSnapshot currentSnapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments) {
            calls.add(new Call(uri, snapshotId, Map.copyOf(arguments)));
            return GovernanceInvokeResult.completed(
                    "OK",
                    "调用成功",
                    Map.of(
                            "content",
                            List.of(Map.of("type", "text", "text", "{\"resolutionStatus\":\"RESOLVED\"}")),
                            "isError",
                            false));
        }

        @Override
        public String fetchInstructions(String snapshotId, String skillId) {
            return "";
        }

        private record Call(String uri, String snapshotId, Map<String, Object> arguments) {}
    }

    private static final class SwitchingModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        volatile String nextToolName = "tool_a";

        @Override
        public String getModelName() {
            return "binding-proof-model";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            int lastUser = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getRole() == MsgRole.USER) lastUser = i;
            }
            boolean sawToolThisTurn = false;
            for (int i = lastUser + 1; i < messages.size(); i++) {
                Msg message = messages.get(i);
                if (message.getRole() == MsgRole.TOOL || message.hasContentBlocks(ToolResultBlock.class)) {
                    sawToolThisTurn = true;
                    break;
                }
            }
            ContentBlock content;
            String finish;
            if (sawToolThisTurn) {
                content = TextBlock.builder().text("已根据工具结果回答。").build();
                finish = "stop";
            } else {
                Map<String, Object> args = Map.of("name", "田沐阳");
                content = ToolUseBlock.builder()
                        .id("proof-" + calls.get())
                        .name(nextToolName)
                        .input(args)
                        .content(JsonUtils.getJsonCodec().toJson(args))
                        .build();
                finish = "tool_calls";
            }
            return Flux.just(ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(content))
                    .finishReason(finish)
                    .build());
        }
    }

    private static final class ApprovalThenChatModel implements Model {
        private final AtomicReference<String> phase = new AtomicReference<>("ask");

        @Override
        public String getModelName() {
            return "approval-proof-model";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean sawTool = messages.stream().anyMatch(m -> m.hasContentBlocks(ToolUseBlock.class)
                    || m.hasContentBlocks(ToolResultBlock.class));
            ContentBlock content;
            String finish;
            if ("ask".equals(phase.get()) && !sawTool) {
                Map<String, Object> args = Map.of("path", "notes/审批记录.md", "content", "经人工审批写入的记录");
                content = ToolUseBlock.builder()
                        .id("approval-tool-001")
                        .name("write_note")
                        .input(args)
                        .content(JsonUtils.getJsonCodec().toJson(args))
                        .build();
                finish = "tool_calls";
            } else {
                phase.set("done");
                content = TextBlock.builder().text("已根据人工决定处理。").build();
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
