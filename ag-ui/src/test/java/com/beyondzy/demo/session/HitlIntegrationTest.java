package com.beyondzy.demo.session;

import static org.junit.jupiter.api.Assertions.*;
import com.beyondzy.demo.contract.ThreadView;
import com.beyondzy.demo.hitl.HitlService;
import io.agentscope.core.model.*;
import io.agentscope.core.message.*;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.session.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** 使用脚本模型驱动真实 Harness、SQLite 和写文件工具，不依赖真实模型服务。 */
class HitlIntegrationTest {
    @TempDir Path workspace;
    static class ScriptModel implements Model {
        final String path;
        ScriptModel() { this("notes/审批记录.md"); }
        ScriptModel(String path) { this.path = path; }
        public String getModelName() { return "本地审批脚本模型"; }
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean asked = messages.stream().anyMatch(m -> m.hasContentBlocks(ToolUseBlock.class));
            ContentBlock content = asked ? TextBlock.builder().text("已根据人工决定处理，本次不再重复申请。").build()
                    : ToolUseBlock.builder().id("approval-tool-001").name("write_note")
                        .input(Map.of("path", path, "content", "经人工审批写入的记录"))
                        .content(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(Map.of("path", path, "content", "经人工审批写入的记录"))).build();
            return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString()).content(List.of(content))
                    .finishReason(asked ? "stop" : "tool_calls").build());
        }
    }
    @Test void 重开后基础视图命中持久索引且答复仍联合最新Inbox() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            sessions.view("test");
        }
        try (var reopened = new ChatSessions(workspace, new ScriptModel())) {
            var current = reopened.view("test");
            assertEquals(0, reopened.viewReadStats().builds(), "重开后的基础视图不应再次重建");
            assertTrue(reopened.viewReadStats().indexHits() > 0);
            assertFalse(current.pendingInteractions().isEmpty());
            var requestId = card(reopened).hitl().requestId();
            reopened.answer("test", requestId, "reject", "校验缓存不会覆盖新答复");
            waitFor(() -> "rejected".equals(card(reopened).hitl().interactionStatus()));
            assertFalse(card(reopened).hitl().canRespond());
        }
    }

    private ThreadView.ToolCardViewModel card(ChatSessions sessions) {
        return sessions.view("test").view().stream().filter(ThreadView.ToolCardViewModel.class::isInstance)
                .map(ThreadView.ToolCardViewModel.class::cast).filter(c -> c.hitl() != null).findFirst().orElseThrow();
    }

    @Test void 增量展示审批与旧视图一致并跟随Inbox答复() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            sessions.projection().sync("test");
            var initial = sessions.projection().store().pending("test");
            assertEquals(1, initial.size());
            assertEquals("pending", ((Map<?, ?>) initial.get(0).item().get("hitl")).get("interactionStatus"));
            String requestId = card(sessions).hitl().requestId();
            sessions.answer("test", requestId, "reject", "验证增量展示");
            waitFor(() -> "rejected".equals(card(sessions).hitl().interactionStatus()) && sessions.snapshot("test").activeRun() == null);
            sessions.projection().sync("test");
            assertEquals(0, sessions.projection().store().pendingCount("test"));
            var page = sessions.projection().store().page("test", 50, null);
            var actual = page.items().stream().filter(item -> item.item().get("kind").equals("tool-card")).findFirst().orElseThrow();
            var expected = sessions.view("test").view().stream().filter(ThreadView.ToolCardViewModel.class::isInstance).findFirst().orElseThrow();
            assertEquals(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(expected),
                    io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(actual.item()));
        }
    }
    private void waitFor(BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Throwable last = null;
        while (System.nanoTime() < limit) {
            try { if (condition.getAsBoolean()) return; } catch (RuntimeException error) { last = error; }
            Thread.sleep(50);
        }
        throw new AssertionError("等待框架处理超时", last);
    }
    private void pending(ChatSessions sessions) throws Exception {
        sessions.submit("test", "first", "请记录审批测试笔记");
        waitFor(() -> !sessions.snapshot("test").pending().isEmpty() && sessions.snapshot("test").activeRun() == null);
        assertEquals("pending", card(sessions).hitl().interactionStatus());
        assertFalse(Files.exists(workspace.resolve("notes/审批记录.md")), "审批前不得产生写入效果");
    }
    private long dispatchCount(ChatSessions sessions) {
        var log = sessions.log("test");
        long count = 0;
        for (var event : log.scan(0, log.head().seq()))
            if (event.type().equals("tool/dispatch") && "write_note".equals(event.data().get("name"))) count++;
        return count;
    }
    @Test void 批准刷新重试与冲突不会重复执行() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            String id = card(sessions).hitl().requestId();
            var receipt = sessions.answer("test", id, "approve", "确认写入");
            waitFor(() -> Files.exists(workspace.resolve("notes/审批记录.md")) && sessions.snapshot("test").activeRun() == null);
            assertEquals("approved", card(sessions).hitl().interactionStatus());
            assertFalse(card(sessions).hitl().canRespond());
            var retry = sessions.answer("test", id, "approve", "确认写入");
            assertFalse(retry.created());
            assertEquals(receipt.commandId(), retry.commandId());
            assertThrows(IllegalStateException.class, () -> sessions.answer("test", id, "reject", "确认写入"));
            assertEquals(1, dispatchCount(sessions));
        }
        try (var reopened = new ChatSessions(workspace, new ScriptModel())) {
            assertEquals("approved", card(reopened).hitl().interactionStatus());
            assertEquals(1, dispatchCount(reopened));
        }
    }
    @Test void 拒绝原因进入上下文且不写文件() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            sessions.answer("test", card(sessions).hitl().requestId(), "reject", "内容需要修改");
            waitFor(() -> card(sessions).hitl().interactionStatus().equals("rejected") && sessions.snapshot("test").activeRun() == null);
            assertEquals("内容需要修改", card(sessions).hitl().reason());
            assertEquals(0, dispatchCount(sessions));
            assertFalse(Files.exists(workspace.resolve("notes/审批记录.md")));
        }
    }
    @Test void 取消后旧卡片不能恢复任务() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            var c = card(sessions);
            sessions.cancelPending("test", c.turnId());
            assertEquals("cancelled", card(sessions).hitl().interactionStatus());
            assertThrows(IllegalStateException.class, () -> sessions.answer("test", c.hitl().requestId(), "approve", ""));
            assertEquals("cancelled", sessions.cancelPending("test", c.turnId()).get("status"));
            assertEquals(0, dispatchCount(sessions));
        }
    }
    @Test void 待答任务重启后仍能批准() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) { pending(sessions); }
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            assertEquals("pending", card(sessions).hitl().interactionStatus());
            sessions.answer("test", card(sessions).hitl().requestId(), "approve", "");
            waitFor(() -> Files.exists(workspace.resolve("notes/审批记录.md")));
        }
    }
    @Test void 已接收未启动的答复在重启后执行() throws Exception {
        ToolUseBlock call; String turn;
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            var c = card(sessions); turn = c.turnId();
            call = io.agentscope.core.util.JsonUtils.getJsonCodec().convertValue(
                    sessions.snapshot("test").pending().getFirst().data().get("call"), ToolUseBlock.class);
        }
        // 此时没有调度器，精确构造持久接收后、执行前的恢复点。
        var store = new io.agentscope.harness.agent.session.WorkspaceSessionLogStore(
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(workspace));
        // 借用未启动的框架会话读取同一日志。
        var agent = io.agentscope.harness.agent.HarnessAgent.builder().name("恢复验证").agentId("session-chat")
                .model(new ScriptModel()).workspace(workspace).sessionLogStore(store).build();
        var session = agent.session(io.agentscope.core.agent.RuntimeContext.builder().userId("local-user").sessionId("test").build());
        var command = new SessionInbox.Command("durable-answer", "respond", turn,
                List.of(UserMessage.builder().textContent("").metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,
                        List.of(new ConfirmResult(true, call)))).build()), List.of(call.getId()));
        new SessionInbox(session.log()).locked(tx -> tx.accept(command));
        assertEquals("processing", new HitlService().interactions(session).get(call.getId()).hitl().interactionStatus());
        session.close(); agent.close();
        try (var reopened = new ChatSessions(workspace, new ScriptModel())) {
            waitFor(() -> Files.exists(workspace.resolve("notes/审批记录.md")));
            waitFor(() -> card(reopened).hitl().interactionStatus().equals("approved"));
            assertEquals(1, dispatchCount(reopened));
        }
    }
    @Test void 批准后工具失败仍保留批准事实() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel("../不允许.md"))) {
            pending(sessions);
            sessions.answer("test", card(sessions).hitl().requestId(), "approve", "");
            waitFor(() -> sessions.snapshot("test").activeRun() == null && card(sessions).status().equals("failed"));
            assertEquals("approved", card(sessions).hitl().interactionStatus());
            assertEquals(1, dispatchCount(sessions));
        }
    }
    @Test void 两端相同提交合并相反提交冲突() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            String id = card(sessions).hitl().requestId();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var gate = new CountDownLatch(1);
                Callable<HitlService.AnswerReceipt> action = () -> { gate.await(); return sessions.answer("test", id, "approve", ""); };
                var first = pool.submit(action); var second = pool.submit(action); gate.countDown();
                var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
                assertEquals(a.commandId(), b.commandId()); assertNotEquals(a.created(), b.created());
            }
            assertThrows(IllegalStateException.class, () -> sessions.answer("test", id, "reject", ""));
            waitFor(() -> Files.exists(workspace.resolve("notes/审批记录.md")));
            assertEquals(1, dispatchCount(sessions));
        }
    }
    @Test void 批准与取消竞争只形成可解释结果() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions); var c = card(sessions);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var gate = new CountDownLatch(1);
                Callable<Boolean> approve = () -> { gate.await(); try { sessions.answer("test", c.hitl().requestId(), "approve", ""); return true; } catch (IllegalStateException e) { return false; } };
                Callable<Boolean> cancel = () -> { gate.await(); try { sessions.cancelPending("test", c.turnId()); return true; } catch (IllegalStateException e) { return false; } };
                var a = pool.submit(approve); var b = pool.submit(cancel); gate.countDown();
                assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            }
            waitFor(() -> sessions.snapshot("test").activeRun() == null && Set.of("approved", "cancelled").contains(card(sessions).hitl().interactionStatus()));
            var h = card(sessions).hitl();
            if (h.interactionStatus().equals("cancelled")) assertEquals(0, dispatchCount(sessions));
            else { assertEquals("approved", h.interactionStatus()); assertEquals(1, dispatchCount(sessions)); }
        }
    }
    @Test void 重连通知无需等待新事件且不伪造日志游标() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            var controller = new com.beyondzy.demo.web.ChatController(sessions);
            var first = controller.interactionEvents("test").next().block(Duration.ofSeconds(5));
            assertNotNull(first); assertEquals("hitl-view-changed", first.event()); assertNull(first.id());
            sessions.answer("test", card(sessions).hitl().requestId(), "reject", "");
            var reconnect = controller.interactionEvents("test").next().block(Duration.ofSeconds(5));
            assertNotNull(reconnect); assertEquals("hitl-view-changed", reconnect.event());
        }
    }

    @Test void 接口返回接收重复与冲突状态码() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            var controller = new com.beyondzy.demo.web.ChatController(sessions);
            String id = card(sessions).hitl().requestId();
            var approved = new com.beyondzy.demo.web.ChatController.ApprovalAnswer("approve", "");
            assertEquals(202, controller.answer("test", id, approved).block(Duration.ofSeconds(10)).getStatusCode().value());
            assertEquals(200, controller.answer("test", id, approved).block(Duration.ofSeconds(10)).getStatusCode().value());
            var conflict = controller.answer("test", id, new com.beyondzy.demo.web.ChatController.ApprovalAnswer("reject", ""))
                    .block(Duration.ofSeconds(10));
            assertEquals(409, conflict.getStatusCode().value());
            assertTrue(conflict.getBody().contains("currentView"));
        }
    }

    @Test void 两端同时提交相反决定只接收一方() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            String id = card(sessions).hitl().requestId();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var gate = new CountDownLatch(1);
                java.util.function.Function<String, Boolean> send = decision -> {
                    try { sessions.answer("test", id, decision, ""); return true; }
                    catch (IllegalStateException error) { return false; }
                };
                var a = pool.submit(() -> { gate.await(); return send.apply("approve"); });
                var b = pool.submit(() -> { gate.await(); return send.apply("reject"); });
                gate.countDown();
                assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            }
            waitFor(() -> Set.of("approved", "rejected").contains(card(sessions).hitl().interactionStatus())
                    && sessions.snapshot("test").activeRun() == null);
            assertEquals(card(sessions).hitl().decision().equals("approved") ? 1 : 0, dispatchCount(sessions));
        }
    }

    @Test void 取消接口幂等并在旧批准冲突时返回当前视图() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            var c = card(sessions);
            var controller = new com.beyondzy.demo.web.ChatController(sessions);
            assertEquals(200, controller.cancel("test", c.turnId()).block(Duration.ofSeconds(10)).getStatusCode().value());
            assertEquals(200, controller.cancel("test", c.turnId()).block(Duration.ofSeconds(10)).getStatusCode().value());
            var stale = controller.answer("test", c.hitl().requestId(),
                    new com.beyondzy.demo.web.ChatController.ApprovalAnswer("approve", ""))
                    .block(Duration.ofSeconds(10));
            assertEquals(409, stale.getStatusCode().value());
            assertTrue(stale.getBody().contains("cancelled"));
            assertEquals(0, dispatchCount(sessions));
        }
    }

    @Test void 普通文本分片不触发完整交互快照通知() throws Exception {
        try (var sessions = new ChatSessions(workspace, new ScriptModel())) {
            pending(sessions);
            String before = sessions.interactionRevision("test");
            var log = sessions.log("test");
            var writer = log.acquire("文本分片验证", Duration.ofSeconds(10));
            try {
                long seq = log.head().seq();
                var text = new SessionEvent(1, UUID.randomUUID().toString(), seq + 1,
                        System.currentTimeMillis(), "model/chunk", writer.owner(), "first", true,
                        io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(Map.of("modelCallId", "增量测试",
                                "chunk", Map.of("id", "增量消息", "content", List.of(TextBlock.builder().text("新增文本").build())))));
                log.commit(writer, UUID.randomUUID().toString(), seq, List.of(text));
            } finally { log.release(writer); }
            assertEquals(before, sessions.interactionRevision("test"));
        }
    }

}
