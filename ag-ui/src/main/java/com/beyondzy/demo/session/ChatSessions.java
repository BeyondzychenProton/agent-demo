package com.beyondzy.demo.session;

import com.beyondzy.demo.hitl.HitlService;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import io.agentscope.core.session.SessionLogStore;
import com.beyondzy.demo.contract.ThreadView;
import com.beyondzy.demo.snapshot.ChatHistory;
import com.beyondzy.demo.snapshot.ThreadViewAssembler;
import com.beyondzy.demo.tools.DocSearchTools;
import com.beyondzy.demo.tools.NoteTools;
import com.beyondzy.demo.tools.OrderTools;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话管理器：单个本地进程持有执行；HTTP 接收命令，SSE 只观察持久事实。
 *
 * <p>本类提供快照、提交、增量与首轮工具审批；steer、inject 与执行中取消不在本轮范围。
 */
public final class ChatSessions implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ChatSessions.class);
    private static final String AGENT = "session-chat";
    private final HitlService hitl = new HitlService();
    private final HarnessAgent agent;
    private final SessionLogStore store;
    private final String userId;
    private final ThreadCatalog catalog;
    private final DisplayViewStore displayViews;
    private final com.beyondzy.demo.readmodel.ProjectionService projection;

    public com.beyondzy.demo.readmodel.ProjectionService projection() { return projection; }
    private final java.util.concurrent.atomic.LongAdder viewIndexHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder viewBuilds = new java.util.concurrent.atomic.LongAdder();

    /** 本进程基础视图读取计数，供性能诊断区分索引命中与日志重建。 */
    record ViewReadStats(long indexHits, long builds) {}

    ViewReadStats viewReadStats() {
        return new ViewReadStats(viewIndexHits.sum(), viewBuilds.sum());
    }
    private final String modelName;
    private record CachedSnapshot(long seq, ChatHistory.Snapshot value) {}
    private final Map<String, CachedSnapshot> snapshots = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CachedSnapshot> eldest) {
                    return size() > 16;
                }
            });

    /** 审批视图只依赖已提交水位；水位不变时不必重复扫描日志与 Inbox。 */
    private record CachedInteractions(long logSeq, long inboxSeq, boolean active, String owner,
                                      Map<String, HitlService.Interaction> value) {}
    private final Map<String, CachedInteractions> interactionViews = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CachedInteractions> eldest) {
                    return size() > 16;
                }
            });

    /** 已完成会话的最新建模任务可直接复用；未结束任务不缓存，避免租约过期后返回过期状态。 */
    private record CachedTask(long logSeq, long inboxSeq, AgentSession.Task value) {}
    private final Map<String, CachedTask> latestTasks = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CachedTask> eldest) {
                    return size() > 16;
                }
            });

    /** 只影响审批通知水位的事件；普通文本分片不参与比较。 */
    private static final java.util.Set<String> INTERACTION_EVENT_TYPES = java.util.Set.of(
            "interaction/requested", "interaction/resolved", "turn/cancelled",
            "inbox/started", "run/end");

    private static final class RevisionCursor {
        long seq;
        long interactionSeq;
    }
    private final Map<String, RevisionCursor> revisions = new java.util.concurrent.ConcurrentHashMap<>();

    public ChatSessions(Path workspace, Model model) {
        modelName = model.getModelName();
        this.userId = "local-user";
        store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace.toAbsolutePath()));
        // 演示工具：注册到框架 Toolkit，执行仍由 HarnessAgent 驱动（大纲 M7 / 6.1.9）
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        toolkit.registerTool(new DocSearchTools());
        toolkit.registerTool(new NoteTools(workspace.toAbsolutePath()));
        agent =
                HarnessAgent.builder()
                        .name("Session Chat")
                        .agentId(AGENT)
                        .model(model)
                        .sysPrompt("你是一个简洁的聊天助手，始终用与用户相同的语言回复。"
                                + "需要查订单、检索文档或记录笔记时，优先调用已提供的工具，"
                                + "不要编造工具返回的数据。")
                        .workspace(workspace.toAbsolutePath())
                        .sessionLogStore(store)
                        .toolkit(toolkit)
                        // 查询允许直接执行，写入必须 ASK；真正授权来自确认结果，不能以聊天中的“批准”代替。
                        .permissionContext(approvalPermissions())
                        // 关闭本 demo 用不到的能力，保持最小
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
                        .maxIters(8)
                        .build();
        catalog = new ThreadCatalog(workspace);
        displayViews = new DisplayViewStore(workspace);
        // 启动时恢复已落盘会话，并根据日志水位修复列表读取索引。
        List<String> existing = list();
        existing.forEach(id -> session(id).start());
        existing.forEach(id -> catalog.sync(id, log(id)));
        catalog.retain(java.util.Set.copyOf(existing));
        projection = new com.beyondzy.demo.readmodel.ProjectionService(this::log, workspace);
    }

    /**
     * 审批触发端：规则由平台设置，模型只提出调用，不能自行绕过写入授权。
     * ruleContent 为空表示匹配该工具的全部调用；查询不产生写入效果，因此显式允许。
     */
    private static PermissionContextState approvalPermissions() {
        return PermissionContextState.builder()
                .addAskRule("write_note", new PermissionRule(
                        "write_note", null, PermissionBehavior.ASK, "平台审批策略"))
                .addAllowRule("lookup_order", new PermissionRule(
                        "lookup_order", null, PermissionBehavior.ALLOW, "平台查询策略"))
                .addAllowRule("search_docs", new PermissionRule(
                        "search_docs", null, PermissionBehavior.ALLOW, "平台查询策略"))
                .build();
    }

    public String modelName() {
        return modelName;
    }

    /** 校验会话 id 合法：字母数字、下划线、连字符，1~80 位。 */
    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("非法的会话 ID");
        return value;
    }

    /** 构造框架运行上下文：userId 固定，sessionId 原样使用会话 id（共享键，大纲 3.2）。 */
    private RuntimeContext context(String session) {
        return RuntimeContext.builder().userId(userId).sessionId(id(session)).build();
    }

    public SessionLog log(String session) {
        return session(session).log();
    }

    AgentSession session(String id) {
        return agent.session(context(id));
    }

    /** 列出本 agent 下所有已落盘的会话 id。 */
    public List<String> list() {
        return store.list(RuntimeContext.builder().userId(userId).build()).stream()
                .filter(key -> key.agentId().equals(AGENT))
                .map(key -> key.sessionId())
                .sorted()
                .toList();
    }

    public ThreadCatalog.Page threads(int limit, String cursor) {
        return catalog.page(limit, cursor);
    }

    public ThreadCatalog.Summary thread(String id) {
        return catalog.get(id);
    }

    /** 现算快照：从已提交日志前缀重建消息/卡片视图（大纲 5.4.1，读日志现算，不另存状态）。 */
    public ChatHistory.Snapshot snapshot(String id) {
        SessionLog log = session(id).log();
        var head = log.head();
        boolean idle = head.leaseUntil() <= System.currentTimeMillis();
        CachedSnapshot cached = snapshots.get(id);
        if (idle && cached != null && cached.seq() == head.seq()) return cached.value();
        ChatHistory.Snapshot value = ChatHistory.snapshot(id, log);
        if (idle && ChatHistory.position(id, value.cursor()) == head.seq()
                && value.turns().stream().noneMatch(turn -> "running".equals(turn.status()))) {
            snapshots.put(id, new CachedSnapshot(head.seq(), value));
        }
        return value;
    }

    /**
     * M4 页面契约：把已提交快照组装成 6.1.8 的 SnapshotResponse。
     *
     * <p>先恢复普通消息与工具卡片，再合并执行日志和 Inbox 的审批视图，不落独立视图表。
     * 两份日志各自有水位，发生变化时重读；这里不是执行恢复，不调用模型或续跑。
     * 读取时与本进程的审批提交互斥，但框架后台执行仍可能推进，因此返回后仍需提交侧重查。
     */
    public ThreadView.SnapshotResponse view(String id) {
        return hitl.coordinated(id, () -> {
            // 联合查询期间发生变化则重读；执行水位不代表 Inbox 水位。
            for (int attempt = 0; attempt < 4; attempt++) {
                var log = log(id);
                var head = log.head();
                long seq = head.seq(), inputSeq = log.inbox().head().seq();
                var base = baseView(id, log, head);
                var value = base.view();
                // 没有审批事实时跳过 Inbox 联合查询，普通会话读取成本只跟快照相关。
                if (!base.hasInteractions()) {
                    if (seq == log.head().seq() && inputSeq == log.inbox().head().seq()) return value;
                    continue;
                }
                var interactions = interactions(id, seq, inputSeq, head);
                if (seq == log.head().seq() && inputSeq == log.inbox().head().seq())
                    return hitl.decorate(value, interactions);
            }
            throw new IllegalStateException("交互状态正在更新，请稍后重新查询");
        });
    }

    /** 基础展示与实时审批分离；空闲且水位匹配时跨进程复用，无需重新扫描消息日志。 */
    private DisplayViewStore.Saved baseView(String id, SessionLog log, SessionLog.Head head) {
        boolean idle = head.leaseUntil() <= System.currentTimeMillis();
        if (idle) {
            try {
                var saved = displayViews.read(id, head.seq());
                if (saved != null) {
                    viewIndexHits.increment();
                    return saved;
                }
            } catch (RuntimeException error) {
                logger.warn("读取完整展示索引失败，改用现有快照", error);
            }
        }
        viewBuilds.increment();
        var snapshot = snapshot(id);
        var value = ThreadViewAssembler.assemble(id, snapshot);
        var latest = log.head();
        if (idle && latest.leaseUntil() <= System.currentTimeMillis()
                && latest.seq() == head.seq() && value.asOfSeq() == head.seq()
                && value.activeRun() == null
                && snapshot.turns().stream().noneMatch(turn -> "running".equals(turn.status()))) {
            try {
                displayViews.save(id, head.seq(), snapshot.hasInteractions(), value);
            } catch (RuntimeException error) {
                logger.warn("保存完整展示索引失败，本次仍返回已生成视图", error);
            }
        }
        return new DisplayViewStore.Saved(value.asOfSeq(), snapshot.hasInteractions(), value);
    }

    /** 审批事实是追加式日志，水位不变即可复用上次联合查询结果。 */
    private Map<String, HitlService.Interaction> interactions(
            String id, long logSeq, long inboxSeq, SessionLog.Head head) {
        boolean active = head.owner() != null && head.leaseUntil() > System.currentTimeMillis();
        var cached = interactionViews.get(id);
        if (cached != null && cached.logSeq() == logSeq && cached.inboxSeq() == inboxSeq
                && cached.active() == active && java.util.Objects.equals(cached.owner(), head.owner()))
            return cached.value();
        var value = hitl.interactions(session(id));
        interactionViews.put(id, new CachedInteractions(logSeq, inboxSeq, active, head.owner(), value));
        return value;
    }

    /** 审批入口只负责接入：持久接收由 HitlService 转交框架，工具仍由 Harness 调度。 */
    public HitlService.AnswerReceipt answer(String id, String requestId, String decision, String reason) {
        return hitl.answer(session(id(id)), requestId, decision, reason);
    }
    /** 等待阶段取消；仅作用于指定 turn，不删除会话历史。 */
    public Map<String, String> cancelPending(String id, String turnId) {
        return hitl.cancel(session(id(id)), turnId);
    }
    /**
     * HITL 变化标记：执行相关事实水位 + Inbox 水位 + 当前执行是否持有租约。
     *
     * <p>租约单独参与：最后一批日志提交后才释放租约，序号不变也可能允许操作。
     * 此字符串只比较是否变化，不是跨日志统一游标，不作为 Last-Event-ID 使用。
     */
    public String interactionRevision(String id) {
        var log = log(id);
        var head = log.head();
        long inboxSeq = log.inbox().head().seq();
        boolean active = head.owner() != null && head.leaseUntil() > System.currentTimeMillis();
        var cursor = revisions.computeIfAbsent(id, key -> initialRevision(id, log));
        synchronized (cursor) {
            // 只在新增事件上推进水位，避免 500ms 轮询反复重扫完整前缀。
            long from = cursor.seq;
            while (from < head.seq()) {
                var batch = log.readAfter(from, 256);
                if (batch.isEmpty()) break;
                long next = from;
                for (var event : batch) {
                    if (event.seq() <= from || event.seq() > head.seq()) continue;
                    next = event.seq();
                    if (INTERACTION_EVENT_TYPES.contains(event.type()))
                        cursor.interactionSeq = event.seq();
                }
                if (next == from) break;
                from = next;
            }
            cursor.seq = from;
            // 文本分片继续走原增量通道，不因每个 token 都让页面重建完整快照。
            return cursor.interactionSeq + ":" + inboxSeq + ":" + active;
        }
    }

    /** 首次通知通道从完整前缀建立一次水位；之后只做增量读取。 */
    private RevisionCursor initialRevision(String id, SessionLog log) {
        var cursor = new RevisionCursor();
        var head = log.head();
        try {
            var saved = displayViews.read(id, head.seq());
            if (saved != null) {
                cursor.seq = head.seq();
                // 通知水位只负责比较变化；首次订阅仍会通知，后续从该已核验位置增量推进。
                cursor.interactionSeq = saved.hasInteractions() ? head.seq() : 0;
                return cursor;
            }
        } catch (RuntimeException error) {
            logger.warn("审批通知读取展示水位失败，改用日志建立水位", error);
        }
        for (var event : log.scan(0, head.seq())) {
            if (INTERACTION_EVENT_TYPES.contains(event.type())) cursor.interactionSeq = event.seq();
        }
        cursor.seq = head.seq();
        return cursor;
    }

    /** 页面展示快照可跨进程复用；运行中始终以最新日志视图为准。 */
    public ThreadCatalog.DisplaySnapshot display(String id) {
        SessionLog log = log(id);
        var head = log.head();
        boolean idle = head.leaseUntil() <= System.currentTimeMillis();
        if (idle) {
            ThreadCatalog.DisplaySnapshot saved = catalog.display(id, head.seq());
            var latest = log.head();
            if (saved != null && latest.seq() == head.seq()
                    && latest.leaseUntil() <= System.currentTimeMillis()) return saved;
            ThreadCatalog.DisplaySnapshot previous = catalog.previousDisplay(id, head.seq());
            if (previous != null) {
                ThreadCatalog.DisplaySnapshot updated = incrementalDisplay(log, head, previous);
                if (updated != null) {
                    try {
                        catalog.saveDisplay(id, updated);
                    } catch (RuntimeException error) {
                        // 索引写入失败不影响本次读取，下次仍可重新合并或重建。
                        logger.warn("会话展示快照增量写入失败", error);
                    }
                    return updated;
                }
            }
        }
        ChatHistory.Snapshot snapshot = snapshot(id);
        List<ThreadCatalog.DisplayMessage> messages = new ArrayList<>();
        for (Msg message : snapshot.messages()) {
            MsgRole role = message.getRole();
            if (role != MsgRole.USER && role != MsgRole.ASSISTANT) continue;
            String content = message.getTextContent();
            if (content == null || content.isBlank()) continue;
            messages.add(new ThreadCatalog.DisplayMessage(
                    message.getId(), role == MsgRole.USER ? "user" : "assistant", content));
        }
        List<ThreadCatalog.DisplayTurn> turns = snapshot.turns().stream()
                .map(turn -> new ThreadCatalog.DisplayTurn(turn.runId(), turn.status()))
                .toList();
        ThreadCatalog.DisplaySnapshot value = new ThreadCatalog.DisplaySnapshot(
                ChatHistory.position(id, snapshot.cursor()), List.copyOf(messages), turns);
        var latest = log.head();
        if (idle && latest.seq() == value.seq()
                && latest.leaseUntil() <= System.currentTimeMillis()
                && turns.stream().noneMatch(turn -> "running".equals(turn.status()))) {
            try {
                catalog.saveDisplay(id, value);
            } catch (RuntimeException error) {
                // 展示快照写入失败时继续使用已经从日志重建的结果。
                logger.warn("会话展示快照写入失败，下次将从日志重建", error);
            }
        }
        return value;
    }

    /** 只读取旧快照之后的事实；水位变化或事件无法连续读取时退回完整重建。 */
    static ThreadCatalog.DisplaySnapshot incrementalDisplay(
            SessionLog log, SessionLog.Head head, ThreadCatalog.DisplaySnapshot previous) {
        List<SessionEvent> added = new ArrayList<>();
        long cursor = previous.seq();
        while (cursor < head.seq()) {
            List<SessionEvent> batch = log.readAfter(cursor, 256);
            if (batch.isEmpty()) return null;
            long next = cursor;
            for (SessionEvent event : batch) {
                if (event.seq() <= cursor || event.seq() > head.seq()) continue;
                if (event.seq() != next + 1 || "migration/baseline".equals(event.type())) return null;
                added.add(event);
                next = event.seq();
            }
            if (next == cursor) return null;
            cursor = next;
        }
        var latest = log.head();
        if (latest.seq() != head.seq() || latest.leaseUntil() > System.currentTimeMillis()) return null;

        Map<String, ThreadCatalog.DisplayMessage> messages = new LinkedHashMap<>();
        for (ThreadCatalog.DisplayMessage message : previous.messages()) messages.put(message.id(), message);
        for (Msg message : ChatHistory.messagesFrom(head, added)) {
            MsgRole role = message.getRole();
            if (role != MsgRole.USER && role != MsgRole.ASSISTANT) continue;
            String content = message.getTextContent();
            if (content == null || content.isBlank()) continue;
            messages.put(message.getId(), new ThreadCatalog.DisplayMessage(
                    message.getId(), role == MsgRole.USER ? "user" : "assistant", content));
        }
        Map<String, ThreadCatalog.DisplayTurn> turns = new LinkedHashMap<>();
        for (ThreadCatalog.DisplayTurn turn : previous.turns()) turns.put(turn.runId(), turn);
        for (SessionEvent event : added) {
            String runId = event.executionRunId();
            if (runId == null) continue;
            if ("run/start".equals(event.type())) {
                turns.put(runId, new ThreadCatalog.DisplayTurn(runId, "interrupted"));
            } else if ("run/end".equals(event.type())) {
                turns.put(runId, new ThreadCatalog.DisplayTurn(
                        runId, String.valueOf(event.data().get("status"))));
            }
        }
        return new ThreadCatalog.DisplaySnapshot(head.seq(),
                List.copyOf(messages.values()), List.copyOf(turns.values()));
    }

    /** 提交一次新任务：一条用户消息触发一次 run。 */
    public AgentSession.Task submit(String session, String requestId, String text) {
        validateText(text);
        AgentSession.Task task = session(session).submit(id(requestId), text);
        try {
            catalog.submitted(id(session), text);
        } catch (RuntimeException error) {
            // 摘要写入失败不能使已经接受的执行被误报为提交失败。
            logger.warn("会话摘要写入失败，重启时将从日志修复", error);
        }
        return task;
    }

    /** 持久命令在接收时即有 commandId；runId 允许等调度启动后才生成。 */
    public AgentSession.Task task(String sessionId, String commandId) {
        return session(sessionId).task(commandId);
    }

    /**
     * 只读 Inbox 判断最后一个执行命令是否仍未收口。
     *
     * <p>框架的 {@link AgentSession#tasks()} 会为每个历史命令重扫完整执行日志，用在连接握手
     * 上会让空闲会话也按 token 数变慢。Inbox 已记录 accepted/opened/closed/rejected，
     * 按同一 turn 的最后状态即可判断 queued / running / 已结束，成本只跟命令数有关。
     */
    public AgentSession.Task pendingTask(String sessionId) {
        SessionLog inbox = log(sessionId).inbox();
        long inboxSeq = inbox.head().seq();
        if (inboxSeq == 0) return null;
        SessionInbox.Command last = null;
        Map<String, String> turnState = new HashMap<>();
        Map<String, String> openedRun = new HashMap<>();
        var rejected = new HashSet<String>();
        for (SessionEvent event : inbox.scan(0, inboxSeq)) {
            switch (event.type()) {
                case "inbox/accepted" -> {
                    var command = event.payload(SessionInbox.Command.class);
                    if (!command.execution()) break;
                    last = command;
                    turnState.put(command.turnId(), "queued");
                }
                case "inbox/opened" -> {
                    if (event.turnId() == null) break;
                    turnState.put(event.turnId(), "open");
                    openedRun.put(event.turnId(), event.executionRunId());
                }
                case "inbox/closed" -> {
                    if (event.turnId() != null) turnState.put(event.turnId(), "closed");
                }
                case "inbox/rejected" ->
                        rejected.add(String.valueOf(event.data().get("commandId")));
                default -> { }
            }
        }
        if (last == null || rejected.contains(last.id())) return null;
        String state = turnState.get(last.turnId());
        if ("queued".equals(state))
            return new AgentSession.Task(last.id(), last.turnId(), null, "queued", null);
        if ("open".equals(state)) {
            var head = log(sessionId).head();
            boolean active = head.owner() != null && head.leaseUntil() > System.currentTimeMillis();
            return new AgentSession.Task(last.id(), last.turnId(), openedRun.get(last.turnId()),
                    active ? "running" : "interrupted", null);
        }
        return null;
    }

    /** 重连从框架持久命令恢复最新进度，不依赖易失的执行编号映射。 */
    public AgentSession.Task latestTask(String sessionId) {
        var log = log(sessionId);
        long seq = log.head().seq(), inboxSeq = log.inbox().head().seq();
        var cached = latestTasks.get(sessionId);
        if (cached != null && cached.logSeq() == seq && cached.inboxSeq() == inboxSeq)
            return cached.value();
        var tasks = session(sessionId).tasks();
        var value = tasks.isEmpty() ? null : tasks.getLast();
        // 框架按每个持久命令重建 Task，未结束时会随租约变化；只缓存已经收口的结果。
        if (value == null || value.settled())
            latestTasks.put(sessionId, new CachedTask(seq, inboxSeq, value));
        return value;
    }

    /** 从 after 水位之后读取已提交事件（供 SSE 增量通道 readAfter，大纲 5.4.2 第 2 步）。 */
    public List<SessionEvent> events(String session, long after, int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("limit 必须在 1~256 之间");
        SessionLog log = log(session);
        if (after > log.head().seq())
            throw new IllegalArgumentException("游标已超过历史水位，请重新加载快照");
        return log.readAfter(after, limit);
    }

    private static void validateText(String text) {
        if (text == null || text.isBlank() || text.length() > 8000)
            throw new IllegalArgumentException("消息长度需在 1~8000 字符之间");
    }

    @Override
    public void close() {
        projection.close();
        agent.close();
    }
}
