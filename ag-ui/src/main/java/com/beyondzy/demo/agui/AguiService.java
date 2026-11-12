package com.beyondzy.demo.agui;

import com.beyondzy.demo.agui.AguiEventProjection;
import com.beyondzy.demo.auth.support.UserContext;
import com.beyondzy.demo.session.ChatSessions;
import com.beyondzy.demo.session.ThreadCatalog;
import java.util.function.Supplier;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import io.agentscope.harness.agent.session.AgentSession;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AG-UI 薄适配层：把本 demo 的 HarnessAgent + SessionLog 主干，翻译成 CopilotKit 能消费的
 * AG-UI 标准事件流与线程协议。
 *
 * <p>设计口径（对应改写后的大纲 5.2.2 / 5.4）：
 * <ul>
 *   <li>会话事实留在 SessionLog，列表摘要从执行日志与 Inbox 派生；
 *   <li>执行仍由 {@link ChatSessions} 提交，本类只做“事件翻译 + 协议外壳”；
 *   <li>聊天与工具事件沿 AG-UI 输出；审批操作走独立服务端视图，本层不声明 CopilotKit 原生 HITL 能力。
 * </ul>
 *
 * <p>注意三个编号：客户端 runId 标识协议流；commandId 在命令接收时已存在；
 * 实际 executionRunId 由框架稍后启动执行时生成。排队回执没有实际执行编号是正常状态，
 * 不能用它作为非空映射值，也不能令投影在编号为空时放弃执行筛选。
 */
@Service
public final class AguiService {

    /** CopilotKit 固定使用的单一路由 agentId（最小 demo 不做多 agent 路由）。 */
    public static final String AGENT_ID = "chat";

    private final ChatSessions sessions;
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public AguiService(ChatSessions sessions) {
        this.sessions = sessions;
    }

    // ===================== /info：Runtime 能力声明 =====================

    /** GET /agui/run/info：CopilotKit 启动时探测后端能力与线程端点。 */
    public Map<String, Object> info() {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("id", AGENT_ID);
        agent.put("name", "Session Chat");
        agent.put("description", "基于 SessionLog 的最小聊天助手");
        // 只声明最小能力：线程管理开，其余高级能力全关
        Map<String, Boolean> capabilities =
                new LinkedHashMap<>(
                        Map.of(
                                "threads", true,
                                "sharedState", false,
                                "frontendTools", false,
                                "humanInTheLoop", false));
        agent.put("capabilities", capabilities);
        agent.put("className", "HarnessAgent");

        Map<String, Object> threadEndpoints =
                new LinkedHashMap<>(Map.of("list", true, "inspect", true, "mutations", true, "realtimeMetadata", false));

        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("mounted", sessions.mcpToolsMounted());
        mcp.put("registeredTools", sessions.mcpRegisteredTools());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "2.0.0");
        result.put("agents", Map.of(AGENT_ID, agent));
        result.put("audioFileTranscriptionEnabled", false);
        result.put("mode", "sse");
        result.put("threadEndpoints", threadEndpoints);
        result.put("suggestions", false);
        result.put("intelligence", Map.of("wsUrl", ""));
        result.put("a2uiEnabled", false);
        result.put("openGenerativeUIEnabled", false);
        result.put("licenseStatus", "valid");
        result.put("telemetryDisabled", true);
        result.put("mcp", mcp);
        return result;
    }

    // ===================== 线程列表 / 创建 =====================

    /** GET /agui/run/threads：从 SessionLog 派生的摘要分页读取线程。 */
    public Map<String, Object> listThreads(int limit, String cursor) {
        ThreadCatalog.Page page = sessions.threads(limit, cursor);
        List<Map<String, Object>> threads = new ArrayList<>();
        for (ThreadCatalog.Summary summary : page.threads()) {
            threads.add(threadInfo(summary.id(), summary));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threads", threads);
        body.put("nextCursor", page.nextCursor());
        body.put("joinCode", null);
        return body;
    }

    /**
     * POST /agui/run/threads：新建线程只发一个合法 id，不预建、不落盘；
     * 首次发消息提交时由 SessionLog 惰性创建。
     */
    public Map<String, Object> createThread(Map<String, Object> request) {
        String threadId = newThreadId();
        Map<String, Object> info = threadInfo(threadId);
        if (request != null && request.get("name") instanceof String name && !name.isBlank()) {
            info.put("name", name);
        }
        return info;
    }

    /** PATCH /threads/{id} / archive / delete：最小 demo 不落表，仅回显成功，不删日志。 */
    public Map<String, Object> noopThread(String threadId, Map<String, Object> request) {
        Map<String, Object> info = threadInfo(threadId);
        if (request != null && request.get("name") instanceof String name && !name.isBlank()) {
            info.put("name", name);
        }
        return info;
    }

    public Map<String, Object> renameThread(String threadId, Map<String, Object> request) {
        Object value = request.get("name");
        if (!(value instanceof String name)) throw new IllegalArgumentException("会话名称不能为空");
        return threadInfo(threadId, sessions.renameThread(threadId, name));
    }

    /** 把会话摘要转换为 CopilotKit 线程描述。 */
    private Map<String, Object> threadInfo(String threadId) {
        return threadInfo(threadId, sessions.thread(threadId));
    }

    private Map<String, Object> threadInfo(String threadId, ThreadCatalog.Summary summary) {
        String name = summary == null ? ThreadCatalog.fallback(threadId) : summary.title();
        String now = ThreadCatalog.iso(System.currentTimeMillis());
        String createdAt = summary == null ? now : ThreadCatalog.iso(summary.createdAt());
        String updatedAt = summary == null ? now : ThreadCatalog.iso(summary.updatedAt());
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", threadId);
        info.put("name", name);
        info.put("agentId", AGENT_ID);
        info.put("archived", false);
        info.put("createdAt", createdAt);
        info.put("updatedAt", updatedAt);
        info.put("lastRunAt", updatedAt);
        return info;
    }

    /** GET /threads/{id}/messages：历史消息，供 CopilotKit 直接渲染。 */
    public Map<String, Object> messages(String threadId) {
        List<AguiMessage> list = new ArrayList<>();
        try {
            for (ThreadCatalog.DisplayMessage msg : sessions.display(threadId).messages()) {
                AguiMessage agui = toAguiMessage(msg);
                if (agui != null) list.add(agui);
            }
        } catch (RuntimeException ignored) {
            // 新线程无历史，返回空列表
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", list);
        return body;
    }

    // ===================== /connect：历史快照与活动 Run 续接 =====================

    /**
     * POST /agent/{agentId}/connect（SSE）：先回放历史，再续接仍在执行的 Run。
     * 既无活动执行、也无待执行命令时发 RUN_STARTED → MESSAGES_SNAPSHOT → RUN_FINISHED。
     */
    public Flux<ServerSentEvent<String>> connect(String threadId, String runId) {
        return connect(UserContext.current().orElse("local-user"), threadId, runId);
    }

    public Flux<ServerSentEvent<String>> connect(String userId, String threadId, String runId) {
        // 空 threadId 兜底生成（空线程直接发空握手）
        if (threadId == null || threadId.isBlank()) threadId = newThreadId();
        String rid = (runId == null || runId.isBlank()) ? newRunId() : runId;
        String tid = threadId;
        return blocking(userId, () -> {
            List<AguiMessage> history = new ArrayList<>();
            String activeRunId = null;
            ThreadCatalog.DisplaySnapshot snapshot;
            try {
                snapshot = snapshotForConnect(tid);
            } catch (RuntimeException ignored) {
                return new ConnectState(history, null, null);
            }
            for (ThreadCatalog.DisplayMessage msg : snapshot.messages()) {
                AguiMessage agui = toAguiMessage(msg);
                if (agui != null) history.add(agui);
            }
            activeRunId = snapshot.turns().stream()
                    .filter(turn -> "running".equals(turn.status()))
                    .map(ThreadCatalog.DisplayTurn::runId)
                    .reduce((previous, current) -> current)
                    .orElse(null);
            // queued/starting 回执允许 runId=null，重连应关联持久命令。
            // 只读轻量 Inbox 判断是否仍有未收口命令，避免为每个历史 turn 重扫完整执行日志。
            var task = sessions.pendingTask(tid);
            if (task != null && !task.settled()) {
                return new ConnectState(history, task.runId(), task.commandId());
            }
            if (activeRunId != null) return new ConnectState(history, activeRunId, null);
            return new ConnectState(history, null, null);
        }).flatMapMany(state -> {
            if (state.commandId() != null) {
                // 协议 runId 与实际执行号不同；排队期间也可以先完成连接握手。
                return Flux.<AguiEvent>just(
                                new AguiEvent.RunStarted(tid, rid),
                                new AguiEvent.MessagesSnapshot(tid, rid, state.history()))
                        .map(this::toSse)
                        .concatWith(pollCommand(userId, tid, rid, state.commandId(), "msg-" + UUID.randomUUID(), 0L));
            }
            if (state.activeRunId() != null) {
                String liveRunId = state.activeRunId();
                return Flux.<AguiEvent>just(
                                new AguiEvent.RunStarted(tid, liveRunId),
                                new AguiEvent.MessagesSnapshot(tid, liveRunId, state.history()))
                        .map(this::toSse)
                        .concatWith(pollStream(userId, tid, liveRunId, liveRunId,
                                "msg-" + UUID.randomUUID(), 0L));
            }
            return Flux.<AguiEvent>just(
                            new AguiEvent.RunStarted(tid, rid),
                            new AguiEvent.MessagesSnapshot(tid, rid, state.history()),
                            new AguiEvent.RunFinished(tid, rid))
                    .map(this::toSse);
        });
    }

    private <T> Mono<T> blocking(String userId, Supplier<T> action) {
        return Mono.fromCallable(() -> UserContext.call(userId, action))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private record ConnectState(List<AguiMessage> history, String activeRunId, String commandId) {}

    /** 用户消息可能先于对应的 run/start 事件提交，需要短暂等待日志视图补齐。 */
    private ThreadCatalog.DisplaySnapshot snapshotForConnect(String threadId) {
        var snapshot = sessions.display(threadId);
        for (int attempt = 0; attempt < 20; attempt++) {
            long users = snapshot.messages().stream().filter(msg -> "user".equals(msg.role())).count();
            if (users <= snapshot.turns().size()) break;
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            snapshot = sessions.display(threadId);
        }
        return snapshot;
    }

    // ===================== /run：提交任务并把 committed 事件翻成 AG-UI 流 =====================

    /**
     * POST /agent/{agentId}/run（SSE）：解析用户消息 → 提交给 HarnessAgent →
     * 轮询 committed 日志，把 model/chunk 文本 delta 翻成 TEXT_MESSAGE_*，run/end 收口。
     */
    public Flux<ServerSentEvent<String>> run(String agentId, Map<String, Object> body) {
        return run(UserContext.current().orElse("local-user"), agentId, body);
    }

    public Flux<ServerSentEvent<String>> run(String userId, String agentId, Map<String, Object> body) {
        String threadId = str(body.get("threadId"));
        if (threadId == null || threadId.isBlank()) threadId = newThreadId();
        String runId = str(body.get("runId"));
        if (runId == null || runId.isBlank()) runId = newRunId();

        String userText = lastUserText(body.get("messages"));
        if (userText == null || userText.isBlank()) {
            return Flux.error(new IllegalArgumentException("未收到用户消息"));
        }

        // 提交任务（同步返回 receipt，执行在后台）
        String finalThread = threadId;
        String finalRun = runId;
        String messageId = "msg-" + UUID.randomUUID();

        // 协议要求：run 流的第一个事件必须是 RUN_STARTED，随后才是 TEXT_MESSAGE_* 与 RUN_FINISHED
        Flux<ServerSentEvent<String>> started =
                Flux.just(toSse(new AguiEvent.RunStarted(finalThread, finalRun)));

        return blocking(userId, () -> {
                    // 提交前确定水位，否则快速完成的 chunk/run-end 可能被跳过。
                    long beforeSubmit = sessions.log(finalThread).head().seq();
                    AgentSession.Task task = sessions.submit(finalThread, finalRun, userText);
                    return new Submitted(task, beforeSubmit);
                })
                .flatMapMany(submitted -> started.concatWith(pollCommand(
                        userId,
                        finalThread,
                        finalRun,
                        submitted.task().commandId(),
                        messageId,
                        submitted.afterSeq())));
    }

    private record Submitted(AgentSession.Task task, long afterSeq) {}

    /**
     * 按命令观察实际执行，覆盖“已接收但尚未启动”的阶段。
     *
     * <p>runId 为空时不推进游标，也不以 null 放开投影筛选，避免串入其他执行。
     * 执行编号生成后固定筛选目标；已快速结束的执行仍从提交前水位补读。
     * 页面断开只停止观察，不撤销持久命令。
     */
    private Flux<ServerSentEvent<String>> pollCommand(
            String userId, String threadId, String protocolRunId, String commandId, String messageId, long afterSeq) {
        return Flux.defer(() -> {
            AtomicLong cursor = new AtomicLong(afterSeq);
            AtomicReference<AguiEventProjection> projection = new AtomicReference<>();
            return Flux.interval(Duration.ZERO, Duration.ofMillis(300))
                    .onBackpressureDrop()
                    .concatMap(tick -> blocking(userId, () -> {
                        if (projection.get() == null) {
                            var task = sessions.task(threadId, commandId);
                            if ("rejected".equals(task.status())) {
                                throw new IllegalStateException("任务命令未被执行接受，请查询当前会话状态");
                            }
                            if (task.runId() == null) return List.<AguiEvent>of();
                            projection.set(new AguiEventProjection(threadId, protocolRunId, task.runId(), messageId));
                        }
                        var events = sessions.events(threadId, cursor.get(), 128);
                        events.forEach(event -> cursor.set(event.seq()));
                        return projection.get().apply(events);
                    }), 1)
                    .flatMapIterable(events -> events)
                    .takeUntil(AguiService::isRunFinished)
                    .map(this::toSse);
        });
    }

    /** 无命令关联的既有活动执行，从明确水位重放；断 SSE 只停观察，不清理凭证。 */
    private Flux<ServerSentEvent<String>> pollStream(
            String userId, String threadId, String inputRunId, String targetRunId, String messageId, long replayFrom) {
        // 重连起点由调用方明确指定，不在这里重新获取最新 head。
        return blocking(userId, () -> replayFrom)
                .flatMapMany(
                        startSeq -> {
                            AtomicLong cursor = new AtomicLong(startSeq);
                            AguiEventProjection projection =
                                    new AguiEventProjection(
                                            threadId, inputRunId, targetRunId, messageId);
                            return Flux.interval(Duration.ZERO, Duration.ofMillis(300))
                                    .onBackpressureDrop()
                                    .concatMap(
                                            tick ->
                                                    blocking(userId, () ->
                                                                            sessions.events(
                                                                                    threadId,
                                                                                    cursor.get(),
                                                                                    128)),
                                            1)
                                    .flatMapIterable(
                                            events -> {
                                                events.forEach(ev -> cursor.set(ev.seq()));
                                                return projection.apply(events);
                                            })
                                    .takeUntil(AguiService::isRunFinished)
                                    .map(this::toSse);
                        });
    }

    // ---- 事件翻译：SessionLog 帧 → AG-UI 事件（实现见 AguiEventProjection） ----

    /** 判断是否为收口的 RUN_FINISHED（收到即结束流）。 */
    private static boolean isRunFinished(AguiEvent event) {
        return event instanceof AguiEvent.RunFinished;
    }

    // ---- 历史消息转换 ----

    /** 把框架 Msg 转成 AG-UI 消息；SYSTEM 与空文本忽略。 */
    private static AguiMessage toAguiMessage(Msg msg) {
        if (msg.getRole() == MsgRole.SYSTEM) return null;
        String text = msg.getTextContent();
        if (text == null || text.isBlank()) return null;
        return switch (msg.getRole()) {
            case USER -> AguiMessage.userMessage(msg.getId(), text);
            case ASSISTANT -> AguiMessage.assistantMessage(msg.getId(), text);
            default -> null;
        };
    }

    /** 把已缓存的页面展示消息转成 AG-UI 消息。 */
    private static AguiMessage toAguiMessage(ThreadCatalog.DisplayMessage msg) {
        return switch (msg.role()) {
            case "user" -> AguiMessage.userMessage(msg.id(), msg.text());
            case "assistant" -> AguiMessage.assistantMessage(msg.id(), msg.text());
            default -> null;
        };
    }

    /** 从请求 messages 数组取最后一条 user 文本（兼容字符串内容与块数组内容）。 */
    @SuppressWarnings("unchecked")
    private static String lastUserText(Object messagesObj) {
        if (!(messagesObj instanceof List<?> messages)) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object m = messages.get(i);
            if (!(m instanceof Map<?, ?> msg)) continue;
            String role = String.valueOf(msg.get("role")).toLowerCase();
            if (!"user".equals(role)) continue;
            Object content = msg.get("content");
            if (content instanceof String s && !s.isBlank()) return s;
            if (content instanceof List<?> blocks) {
                StringBuilder sb = new StringBuilder();
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> block && "text".equals(block.get("type"))
                            && block.get("text") instanceof String t) sb.append(t);
                }
                if (!sb.isEmpty()) return sb.toString();
            }
            return null;
        }
        return null;
    }

    // ---- 工具方法 ----

    private ServerSentEvent<String> toSse(AguiEvent event) {
        return ServerSentEvent.<String>builder()
                .data(encoder.encodeToJson(event).trim())
                .build();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 生成仅含字母数字的合法会话 id（符合 ChatSessions.id 校验）。 */
    private static String newThreadId() {
        return "thread" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String newRunId() {
        return "run" + UUID.randomUUID().toString().replace("-", "");
    }
}
