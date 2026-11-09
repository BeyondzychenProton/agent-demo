package com.beyondzy.demo.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.session.ChatSessions;
import com.beyondzy.demo.tools.DocSearchTools;
import com.beyondzy.demo.tools.NoteTools;
import com.beyondzy.demo.tools.OrderTools;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class GovernanceToolAssemblyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URI = "driver://mcp/tenant-a/cms-identity-invoke/identity.resolve";
    private static final String OTHER = "driver://mcp/tenant-a/cms-identity-invoke/identity.search";

    @Test
    void mapsDottedToolNameWithoutGuessingTheUri() {
        List<GovernanceToolNames.Assignment> assigned =
                GovernanceToolNames.assign(List.of(tool("identity.resolve", URI, strictSchema())));

        assertEquals(1, assigned.size());
        assertEquals("identity_resolve", assigned.get(0).registeredName());
        assertEquals(URI, assigned.get(0).uri());
        assertTrue(assigned.get(0).registeredName().matches("[A-Za-z0-9_-]{1,64}"));
    }

    @Test
    void disambiguatesSanitizedNameCollisionsByUri() {
        List<GovernanceToolNames.Assignment> assigned =
                GovernanceToolNames.assign(List.of(
                        tool("identity.resolve", URI, strictSchema()),
                        tool("identity_resolve", OTHER, strictSchema())));

        assertEquals(2, assigned.size());
        assertNotEqualsNames(assigned.get(0).registeredName(), assigned.get(1).registeredName());
        assertEquals(URI, uriOf(assigned, assigned.get(0).registeredName()));
        assertEquals(OTHER, uriOf(assigned, assigned.get(1).registeredName()));
        assertTrue(assigned.get(0).registeredName().contains(GovernanceToolNames.digest8(URI)));
        assertTrue(assigned.get(1).registeredName().contains(GovernanceToolNames.digest8(OTHER)));
    }

    @Test
    void keepsSchemaConstraintsAndDoesNotReplaceLocalTools() {
        Map<String, Object> schema = strictSchema();
        Toolkit toolkit = localToolkit(Path.of("target", "governance-note-workspace"));
        RecordingGateway gateway = gateway(snapshot("snap_a", List.of(
                tool("identity.resolve", URI, schema),
                tool("lookup_order", OTHER, schema),
                tool("missing.schema", "driver://mcp/tenant-a/demo/skipped", null))));
        GovernanceToolMount mount = new GovernanceToolMount(toolkit, gateway);
        mount.install(gateway.snapshot);

        schema.put("hacked", true);
        GovernanceBoundTool identity = (GovernanceBoundTool) toolkit.getTool("identity_resolve");
        assertNotNull(identity);
        assertFalse(identity.getParameters().containsKey("hacked"));
        assertEquals(JSON.valueToTree(strictSchema()), JSON.valueToTree(identity.getParameters()));
        assertEquals("snap_a", identity.snapshotId());
        assertEquals(URI, identity.uri());
        assertEquals("1:1:identity.resolve@1", identity.bindingId());
        assertEquals("9", identity.cardId());
        assertEquals("3", identity.cardVersion());
        assertTrue(identity.getDescription().contains("只读身份查询"));
        assertNullTool(toolkit, "missing_schema");
        assertFalse(toolkit.getTool("lookup_order") instanceof GovernanceBoundTool);
        ToolResultBlock order = toolkit.callTool(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("order-1")
                                .name("lookup_order")
                                .input(Map.of("order_id", "A1001"))
                                .content("{\"order_id\":\"A1001\"}")
                                .build())
                        .build())
                .block();
        assertNotEquals(ToolResultState.ERROR, order.getState());
        assertTrue(text(order).contains("A1001"));
        String governedLookup = mount.registeredNames().stream()
                .filter(name -> !name.equals("identity_resolve"))
                .findFirst()
                .orElseThrow();
        assertNotEqualsNames("lookup_order", governedLookup);
        assertTrue(governedLookup.contains(GovernanceToolNames.digest8(OTHER)));
    }

    @Test
    void executorKeepsBusinessArgumentsAndBoundOuterIdentity() {
        RecordingGateway gateway = gateway(snapshot("snap_bound", List.of(tool("identity.resolve", URI, strictSchema()))));
        Toolkit toolkit = new Toolkit();
        GovernanceToolMount mount = new GovernanceToolMount(toolkit, gateway);
        mount.install(gateway.snapshot);
        gateway.response = GovernanceInvokeResult.completed(
                "OK",
                "调用成功",
                Map.of(
                        "content",
                        List.of(Map.of("type", "text", "text", "{\"resolutionStatus\":\"RESOLVED\"}")),
                        "isError",
                        false));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("city", "杭州");
        nested.put("note", null);
        Map<String, Object> dirty = new LinkedHashMap<>();
        dirty.put("name", "田沐阳");
        dirty.put("pageNum", 1);
        dirty.put("phone", "17600000000");
        dirty.put("mobile", "13900000000");
        dirty.put("subject", "biz-subject");
        dirty.put("snapshotId", "snap_from_model");
        dirty.put("uri", "driver://evil");
        dirty.put("token", "model-token");
        dirty.put("tenantId", "other-tenant");
        dirty.put("bindingId", "forged");
        dirty.put("profile", nested);
        dirty.put("aliases", List.of("甲", "乙"));
        dirty.put("optional", null);
        ToolResultBlock result = toolkit.callTool(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("call-1")
                                .name("identity_resolve")
                                .input(Map.of("name", "田沐阳", "pageNum", 1))
                                .content("{\"name\":\"田沐阳\",\"pageNum\":1}")
                                .build())
                        .input(dirty)
                        .build())
                .block();

        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(text(result).contains("RESOLVED"));
        assertEquals(1, gateway.calls.size());
        RecordingGateway.Invocation call = gateway.calls.get(0);
        assertEquals(URI, call.uri());
        assertEquals("snap_bound", call.snapshotId());
        assertEquals("田沐阳", call.arguments().get("name"));
        assertEquals(1, call.arguments().get("pageNum"));
        assertEquals("17600000000", call.arguments().get("phone"));
        assertEquals("13900000000", call.arguments().get("mobile"));
        assertEquals("biz-subject", call.arguments().get("subject"));
        assertEquals("snap_from_model", call.arguments().get("snapshotId"));
        assertEquals("driver://evil", call.arguments().get("uri"));
        assertEquals("model-token", call.arguments().get("token"));
        assertEquals("other-tenant", call.arguments().get("tenantId"));
        assertEquals("forged", call.arguments().get("bindingId"));
        assertEquals(nested, call.arguments().get("profile"));
        assertEquals(List.of("甲", "乙"), call.arguments().get("aliases"));
        assertTrue(call.arguments().containsKey("optional"));
        assertNull(call.arguments().get("optional"));
        assertEquals("17600000000", dirty.get("phone"));
        assertSame(nested, dirty.get("profile"));
        assertNull(((Map<?, ?>) dirty.get("profile")).get("note"));
    }

    @Test
    void mapsBusinessResultsAndGovernanceErrorsWithoutPretendingSuccess() {
        assertEquals(ToolResultState.SUCCESS, state(GovernanceResults.toToolResult(
                GovernanceInvokeResult.completed("OK", "调用成功", result("NOT_FOUND")))));
        assertTrue(text(GovernanceResults.toToolResult(
                GovernanceInvokeResult.completed("OK", "调用成功", result("CANDIDATES")))).contains("CANDIDATES"));

        ToolResultBlock toolError = GovernanceResults.toToolResult(GovernanceInvokeResult.completed(
                "TOOL_EXECUTION_ERROR",
                "工具执行失败",
                Map.of("content", List.of(Map.of("type", "text", "text", "upstream")), "isError", true)));
        assertEquals(ToolResultState.ERROR, toolError.getState());
        assertTrue(text(toolError).contains("upstream"));
        assertTrue(text(toolError).contains("TOOL_EXECUTION_ERROR"));

        ToolResultBlock denied = GovernanceResults.toToolResult(
                GovernanceInvokeResult.rejected("AUTHZ_DENIED", "当前授权不允许调用该工具", false, false));
        assertEquals(ToolResultState.ERROR, denied.getState());
        assertTrue(text(denied).contains("调用未成功"));
        assertTrue(text(denied).contains("AUTHZ_DENIED"));
        assertFalse(text(denied).contains("17634372150"));

        ToolResultBlock mismatch = GovernanceResults.toToolResult(
                GovernanceInvokeResult.rejected("SNAPSHOT_BINDING_MISMATCH", "Snapshot 绑定不匹配", false, false));
        assertTrue(text(mismatch).contains("SNAPSHOT_BINDING_MISMATCH"));
        assertEquals(ToolResultState.ERROR, mismatch.getState());

        ToolResultBlock rebind = GovernanceResults.toToolResult(
                GovernanceInvokeResult.rejected("CONTRACT_REBIND_REQUIRED", "需要重新装配", false, true));
        assertTrue(text(rebind).contains("CONTRACT_REBIND_REQUIRED"));
        assertTrue(text(rebind).contains("重新装配"));

        ToolResultBlock retryable = GovernanceResults.toToolResult(
                GovernanceInvokeResult.failed("UPSTREAM_NOT_READY", "上游未就绪", true, false));
        assertTrue(text(retryable).contains("未自动重试"));
        assertEquals(ToolResultState.ERROR, retryable.getState());

        ToolResultBlock uncertain = GovernanceResults.toToolResult(
                GovernanceInvokeResult.uncertain("EXECUTION_UNCERTAIN", "结果不确定"));
        assertTrue(text(uncertain).contains("不确定"));
        assertTrue(text(uncertain).contains("不要编造"));
        assertEquals(ToolResultState.ERROR, uncertain.getState());

        ToolResultBlock down = GovernanceResults.toToolResult(
                GovernanceInvokeResult.transport("TRANSPORT_FAILURE", "治理服务不可达"));
        assertEquals(ToolResultState.ERROR, down.getState());
        assertTrue(text(down).contains("调用未成功"));
    }

    @Test
    void newInstanceRemountsWhilePreviousMountStaysBound() {
        RecordingGateway gateway = gateway(snapshot("snap_old", List.of(
                tool("identity.resolve", URI, strictSchema()),
                tool("identity.search", OTHER, strictSchema()))));
        Toolkit oldToolkit = localToolkit(Path.of("target", "governance-old-workspace"));
        GovernanceToolMount oldMount = new GovernanceToolMount(oldToolkit, gateway);
        oldMount.install(gateway.snapshot);
        GovernanceBoundTool oldIdentity = (GovernanceBoundTool) oldToolkit.getTool("identity_resolve");
        String oldSearch = oldMount.registeredNames().stream()
                .filter(name -> !name.equals("identity_resolve"))
                .findFirst()
                .orElseThrow();

        GovernanceSnapshot newer = new GovernanceSnapshot(
                "tenant-a",
                "default",
                "agent-invoke-demo",
                "snap_new",
                List.of(new GovernanceToolView(
                        URI,
                        "identity.resolve",
                        "新的查询描述",
                        renamedSchema(),
                        "1:1:identity.resolve@2",
                        "9",
                        "4",
                        "read")));
        gateway.snapshot = newer;
        Toolkit newToolkit = localToolkit(Path.of("target", "governance-new-workspace"));
        GovernanceToolMount newMount = new GovernanceToolMount(newToolkit, gateway);
        newMount.install(newer);

        assertSame(oldIdentity, oldToolkit.getTool("identity_resolve"));
        assertEquals("snap_old", oldMount.snapshotId());
        assertEquals("snap_old", oldIdentity.snapshotId());
        assertNotNull(oldToolkit.getTool(oldSearch));
        assertEquals(2, oldMount.registeredNames().size());

        GovernanceBoundTool fresh = (GovernanceBoundTool) newToolkit.getTool("identity_resolve");
        assertNotSame(oldIdentity, fresh);
        assertEquals("snap_new", newMount.snapshotId());
        assertEquals("snap_new", fresh.snapshotId());
        assertEquals(URI, fresh.uri());
        assertEquals("新的查询描述", fresh.getDescription().substring(0, "新的查询描述".length()));
        assertEquals(JSON.valueToTree(renamedSchema()), JSON.valueToTree(fresh.getParameters()));
        assertEquals(1, newMount.registeredNames().size());
        assertNotNull(newToolkit.getTool("lookup_order"));
        assertNotNull(oldToolkit.getTool("lookup_order"));
    }

    @Test
    void refusesSecondInstallAfterStartupAssembly() {
        RecordingGateway gateway = gateway(snapshot("snap_bound", List.of(tool("identity.resolve", URI, strictSchema()))));
        Toolkit toolkit = localToolkit(Path.of("target", "governance-sealed-workspace"));
        GovernanceToolMount mount = new GovernanceToolMount(toolkit, gateway);
        mount.install(gateway.snapshot);
        GovernanceBoundTool bound = (GovernanceBoundTool) toolkit.getTool("identity_resolve");

        IllegalStateException denied = assertThrows(IllegalStateException.class, () -> mount.install(snapshot(
                "snap_new", List.of(tool("identity.resolve", URI, renamedSchema())))));
        assertTrue(denied.getMessage().contains("运行中不可替换") || denied.getMessage().contains("重启"));
        assertSame(bound, toolkit.getTool("identity_resolve"));
        assertEquals("snap_bound", mount.snapshotId());
        assertEquals("snap_bound", bound.snapshotId());
        assertEquals(JSON.valueToTree(strictSchema()), JSON.valueToTree(bound.getParameters()));
        assertNotNull(toolkit.getTool("lookup_order"));
    }

    @Test
    void revokedCallKeepsBoundSnapshotAndSurfacesDenial() {
        RecordingGateway gateway = gateway(snapshot("snap_bound", List.of(tool("identity.resolve", URI, strictSchema()))));
        Toolkit toolkit = new Toolkit();
        GovernanceToolMount mount = new GovernanceToolMount(toolkit, gateway);
        mount.install(gateway.snapshot);
        gateway.response = GovernanceInvokeResult.rejected(
                "AUTHZ_DENIED", "当前授权不允许调用该工具", false, false);

        ToolResultBlock denied = toolkit.callTool(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("deny-1")
                                .name("identity_resolve")
                                .input(Map.of("name", "田沐阳"))
                                .content("{\"name\":\"田沐阳\"}")
                                .build())
                        .input(Map.of("name", "田沐阳"))
                        .build())
                .block();

        assertEquals(ToolResultState.ERROR, denied.getState());
        assertTrue(text(denied).contains("AUTHZ_DENIED"));
        assertEquals(1, gateway.calls.size());
        assertEquals("snap_bound", gateway.calls.get(0).snapshotId());
        assertEquals(URI, gateway.calls.get(0).uri());
        assertEquals("snap_bound", mount.snapshotId());
    }

    @Test
    void blankSnapshotDoesNotMutateExistingTools() {
        Toolkit toolkit = localToolkit(Path.of("target", "governance-blank-workspace"));
        GovernanceToolMount mount = new GovernanceToolMount(toolkit, gateway(snapshot("snap_x", List.of())));
        assertThrows(IllegalArgumentException.class, () -> mount.install(new GovernanceSnapshot(
                "tenant-a", "default", "agent-invoke-demo", " ", List.of(tool("identity.resolve", URI, strictSchema())))));
        assertTrue(toolkit.getTool("identity_resolve") == null);
        assertNotNull(toolkit.getTool("lookup_order"));
    }

    @Test
    void systemPromptStaysUnchangedUntilGovernanceIsConfigured() {
        assertEquals(
                "你是一个简洁的聊天助手，始终用与用户相同的语言回复。"
                        + "需要查订单、检索文档或记录笔记时，优先调用已提供的工具，"
                        + "不要编造工具返回的数据。",
                ChatSessions.systemPrompt(false));
        String governed = ChatSessions.systemPrompt(true);
        assertTrue(governed.contains("需要查订单、检索文档或记录笔记时"));
        assertTrue(governed.contains("多名候选人"));
        assertTrue(governed.contains("如实说明"));
        assertTrue(governed.contains("调用未成功") || governed.contains("没有成功"));
        assertTrue(governed.contains("闲聊"));
    }

    @Test
    void harnessUsesBoundSnapshotAndReopenKeepsHistory(@TempDir Path workspace) {
        RecordingGateway gateway = gateway(snapshot("snap_session", List.of(tool("identity.resolve", URI, strictSchema()))));
        gateway.response = GovernanceInvokeResult.completed(
                "OK", "调用成功", result("RESOLVED"));
        Toolkit toolkit = localToolkit(workspace);
        GovernanceToolMount mount = new GovernanceToolMount(toolkit, gateway);
        mount.install(gateway.snapshot);
        ScriptModel model = new ScriptModel();
        try (HarnessAgent agent = harness(workspace, toolkit, model)) {
            assertNotSame(toolkit, agent.getToolkit());
            assertEquals("snap_session", ((GovernanceBoundTool) agent.getToolkit().getTool("identity_resolve")).snapshotId());
            assertEquals("snap_session", mount.snapshotId());
            AgentSession session = agent.session(context());
            session.start();
            AgentSession.Task done = session.await(session.submit("req-query", "帮我查询田沐阳的信息。"))
                    .block(Duration.ofSeconds(20));
            assertNotNull(done);
            assertTrue(done.settled(), done.status() + " " + done.error());
            assertEquals(1, gateway.calls.size());
            assertEquals("snap_session", gateway.calls.get(0).snapshotId());
            assertEquals(URI, gateway.calls.get(0).uri());
            assertEquals("田沐阳", gateway.calls.get(0).arguments().get("name"));
            assertFalse(gateway.calls.get(0).arguments().containsKey("phone"));
            assertTrue(transcript(session).contains("帮我查询田沐阳的信息。"));
            session.close();
        }

        gateway.calls.clear();
        gateway.snapshot = snapshot("snap_restart", List.of(tool("identity.resolve", URI, strictSchema())));
        Toolkit restarted = localToolkit(workspace);
        GovernanceToolMount rebound = new GovernanceToolMount(restarted, gateway);
        rebound.install(gateway.snapshot);
        assertEquals("snap_session", mount.snapshotId());
        assertThrows(IllegalStateException.class, () -> mount.install(gateway.snapshot));
        try (HarnessAgent agent = harness(workspace, restarted, model)) {
            AgentSession session = agent.session(context());
            session.start();
            assertTrue(transcript(session).contains("帮我查询田沐阳的信息。"));
            assertEquals("snap_restart", ((GovernanceBoundTool) agent.getToolkit().getTool("identity_resolve")).snapshotId());
            assertEquals("snap_restart", rebound.snapshotId());
            int before = gateway.calls.size();
            AgentSession.Task chat = session.await(session.submit("req-hello", "你好"))
                    .block(Duration.ofSeconds(20));
            assertNotNull(chat);
            assertTrue(chat.settled(), chat.status() + " " + chat.error());
            assertEquals(before, gateway.calls.size());
            session.close();
        }
    }

    private static void assertNotEqualsNames(String left, String right) {
        assertFalse(left.equals(right));
    }

    private static String uriOf(List<GovernanceToolNames.Assignment> assigned, String name) {
        return assigned.stream()
                .filter(item -> item.registeredName().equals(name))
                .map(GovernanceToolNames.Assignment::uri)
                .findFirst()
                .orElseThrow();
    }

    private static void assertNullTool(Toolkit toolkit, String name) {
        assertTrue(toolkit.getTool(name) == null);
    }

    private static ToolResultState state(ToolResultBlock block) {
        return block.getState();
    }

    private static String text(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().get(0)).getText();
    }

    private static Map<String, Object> result(String status) {
        return Map.of(
                "content",
                List.of(Map.of("type", "text", "text", "{\"resolutionStatus\":\"" + status + "\"}")),
                "isError",
                false);
    }

    private static Toolkit localToolkit(Path workspace) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        toolkit.registerTool(new DocSearchTools());
        toolkit.registerTool(new NoteTools(workspace));
        return toolkit;
    }

    private static HarnessAgent harness(Path workspace, Toolkit toolkit, Model model) {
        return HarnessAgent.builder()
                .name("Session Chat")
                .agentId("session-chat")
                .model(model)
                .sysPrompt(ChatSessions.systemPrompt(true))
                .workspace(workspace)
                .sessionLogStore(new WorkspaceSessionLogStore(new LocalFilesystem(workspace)))
                .toolkit(toolkit)
                .permissionContext(PermissionContextState.builder()
                        .addAllowRule("lookup_order", new PermissionRule(
                                "lookup_order", null, PermissionBehavior.ALLOW, "平台查询策略"))
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

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("local-user").sessionId("gov-session").build();
    }

    private static String transcript(AgentSession session) {
        StringBuilder text = new StringBuilder();
        for (Msg message : session.transcript().messages()) {
            if (message.getTextContent() != null) text.append(message.getTextContent()).append('\n');
        }
        return text.toString();
    }

    private static RecordingGateway gateway(GovernanceSnapshot snapshot) {
        RecordingGateway gateway = new RecordingGateway();
        gateway.snapshot = snapshot;
        gateway.response = GovernanceInvokeResult.completed("OK", "调用成功", result("RESOLVED"));
        return gateway;
    }

    private static GovernanceSnapshot snapshot(String snapshotId, List<GovernanceToolView> tools) {
        return new GovernanceSnapshot("tenant-a", "default", "agent-invoke-demo", snapshotId, tools);
    }

    private static GovernanceToolView tool(String name, String uri, Map<String, Object> schema) {
        return new GovernanceToolView(
                uri,
                name,
                "identity.resolve".equals(name) ? "只读身份查询" : "新的查询描述",
                schema,
                "1:1:" + name + "@1",
                "9",
                "3",
                "read");
    }

    private static Map<String, Object> strictSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "maxLength", 20));
        properties.put("pageNum", Map.of("type", "integer", "minimum", 1));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> renamedSchema() {
        Map<String, Object> schema = strictSchema();
        schema.put("description", "replaced");
        return schema;
    }

    static final class RecordingGateway implements GovernanceGateway {
        GovernanceSnapshot snapshot;
        GovernanceInvokeResult response;
        RuntimeException failure;
        final List<Invocation> calls = new CopyOnWriteArrayList<>();

        record Invocation(String uri, String snapshotId, Map<String, Object> arguments) {}

        @Override
        public GovernanceSnapshot currentSnapshot() {
            if (failure != null) throw failure;
            return snapshot;
        }

        @Override
        public GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments) {
            calls.add(new Invocation(uri, snapshotId, new LinkedHashMap<>(arguments)));
            return response;
        }
    }

    static final class ScriptModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "治理脚本模型";
        }

        @Override
        public Flux<io.agentscope.core.model.ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            String latestUser = "";
            for (Msg message : messages) {
                if (message.getRole() == MsgRole.USER && message.getTextContent() != null) {
                    latestUser = message.getTextContent();
                }
            }
            boolean sawTool = messages.stream().anyMatch(message ->
                    message.getRole() == MsgRole.TOOL || message.hasContentBlocks(ToolResultBlock.class));
            ContentBlock content;
            String finish;
            if (latestUser.contains("你好")) {
                content = TextBlock.builder().text("你好，我在。").build();
                finish = "stop";
            } else if (!sawTool) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("name", "田沐阳");
                args.put("pageNum", 1);
                content = ToolUseBlock.builder()
                        .id("gov-call-" + calls.get())
                        .name("identity_resolve")
                        .input(args)
                        .content(JsonUtils.getJsonCodec().toJson(args))
                        .build();
                finish = "tool_calls";
            } else {
                content = TextBlock.builder().text("已根据工具结果回答。").build();
                finish = "stop";
            }
            return Flux.just(io.agentscope.core.model.ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(content))
                    .finishReason(finish)
                    .build());
        }
    }
}
