package com.beyondzy.demo.session;

import com.beyondzy.demo.auth.support.SubmitCredentialCapture;
import com.beyondzy.demo.auth.support.UserContext;
import com.beyondzy.demo.governance.AssemblyBindingStore;
import com.beyondzy.demo.governance.GovernanceAssemblySupport;
import com.beyondzy.demo.governance.GovernanceBoundTool;
import com.beyondzy.demo.governance.GovernanceGateway;
import com.beyondzy.demo.governance.GovernanceRuntimeStatus;
import com.beyondzy.demo.governance.GovernanceSnapshot;
import com.beyondzy.demo.governance.GovernanceSnapshotFetch;
import com.beyondzy.demo.governance.GovernanceToolMount;
import com.beyondzy.demo.governance.HarnessGenerationPool;
import com.beyondzy.demo.governance.PreparedGovernanceAssembly;
import com.beyondzy.demo.governance.RunCredentialLifecycle;
import com.beyondzy.demo.governance.RunCredentialRegistry;
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
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.core.session.SessionLogStore;
import com.beyondzy.demo.display.DisplayIndexService;
import com.beyondzy.demo.display.DisplayViewStore;
import com.beyondzy.demo.readmodel.ChatHistory;
import com.beyondzy.demo.readmodel.NoopReadModel;
import com.beyondzy.demo.readmodel.ProjectionService;
import com.beyondzy.demo.readmodel.ReadModelPersistenceService;
import com.beyondzy.demo.readmodel.ThreadView;
import com.beyondzy.demo.readmodel.ThreadViewAssembler;
import com.beyondzy.demo.tools.DocSearchTools;
import com.beyondzy.demo.tools.NoteTools;
import com.beyondzy.demo.tools.OrderTools;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话管理器：单个本地进程持有执行；HTTP 接收命令，SSE 只观察持久事实。
 *
 * <p>本类提供快照、提交、增量与首轮工具审批；steer、inject 与执行中取消不在本轮范围。
 *
 * <p>治理装配按 run 绑定：新任务使用最新成功装配；进行中/待审批任务留在提交时的 Harness 一代。
 * 排队任务在 submit 时绑定，同一会话未收口前不会跨代。旧 Harness 仅在引用归零后关闭。
 */
public final class ChatSessions implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ChatSessions.class);
    private static final String AGENT = "session-chat";
    private static final long GOVERNANCE_POLL_SECONDS = 60L;
    private final HitlService hitl = new HitlService();
    private final Object agentLock = new Object();
    private final HarnessGenerationPool harnessPool;
    private final AssemblyBindingStore assemblyBindings;
    private final GovernanceGateway governance;
    private final Model model;
    private final Path workspace;
    private final AtomicReference<String> adoptedEtag = new AtomicReference<>();
    private final AtomicReference<Instant> lastRefreshAt = new AtomicReference<>();
    private final AtomicReference<String> lastRefreshFailure = new AtomicReference<>();
    private final ScheduledExecutorService governanceRefreshExecutor;
    private final ExecutorService assemblyLifecycleExecutor;
    private final SessionLogStore store;
    private final String defaultUserId;
    private final AgentSessionService agentSessions;
    private final ThreadCatalog catalog;
    private final DisplayViewStore displayViews;
    private final ProjectionService projection;

    public ProjectionService projection() { return projection; }
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
        this(workspace, model, null, "local-user", null, null, null, null);
    }

    /**
     * 单测/本地装配入口：使用给定 SessionLogStore 与可选治理网关，读模型为 no-op（不碰 MySQL）。
     */
    public ChatSessions(
            Path workspace, Model model, SessionLogStore sessionLogStore, GovernanceGateway governance) {
        this(workspace, model, sessionLogStore, "local-user", null, NoopReadModel.INSTANCE, null, governance);
    }

    /** 同上，可指定默认 userId（仍可由 {@link UserContext} 覆盖）。 */
    public ChatSessions(
            Path workspace,
            Model model,
            SessionLogStore sessionLogStore,
            String defaultUserId,
            GovernanceGateway governance) {
        this(
                workspace,
                model,
                sessionLogStore,
                defaultUserId == null ? "local-user" : defaultUserId,
                null,
                NoopReadModel.INSTANCE,
                null,
                governance);
    }

    public ChatSessions(Path workspace, Model model,
            SessionLogStore sessionLogStore, String userId,
            DisplayIndexService displayIndex, ReadModelPersistenceService readModelPersistence) {
        this(workspace, model, sessionLogStore, userId, displayIndex, readModelPersistence, null, null);
    }

    public ChatSessions(Path workspace, Model model,
            SessionLogStore sessionLogStore, String userId,
            DisplayIndexService displayIndex, ReadModelPersistenceService readModelPersistence,
            AgentSessionService agentSessionService) {
        this(workspace, model, sessionLogStore, userId, displayIndex, readModelPersistence,
                agentSessionService, null);
    }

    public ChatSessions(Path workspace, Model model,
            SessionLogStore sessionLogStore, String userId,
            DisplayIndexService displayIndex, ReadModelPersistenceService readModelPersistence,
            AgentSessionService agentSessionService, GovernanceGateway governance) {
        if (sessionLogStore == null || readModelPersistence == null)
            throw new IllegalArgumentException("ChatSessions 需要会话存储和读模型持久化服务");
        modelName = model.getModelName();
        this.model = model;
        this.workspace = workspace.toAbsolutePath();
        this.governance = governance;
        this.defaultUserId = userId;
        this.agentSessions = agentSessionService;
        store = sessionLogStore;
        this.assemblyBindings = new AssemblyBindingStore(this.workspace);
        this.harnessPool = new HarnessGenerationPool(this::buildHarness, agentLock);
        this.assemblyLifecycleExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "assembly-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        PreparedGovernanceAssembly initial = null;
        if (governance != null) {
            try {
                GovernanceSnapshotFetch fetch = governance.fetchCurrent(null);
                if (!fetch.notModified() && fetch.snapshot() != null) {
                    initial = GovernanceAssemblySupport.prepare(
                            this.workspace, governance, fetch.snapshot(), fetch.etag());
                    adoptedEtag.set(fetch.etag());
                    lastRefreshAt.set(Instant.now());
                }
            } catch (RuntimeException error) {
                lastRefreshFailure.set(error.toString());
                logger.warn("首次拉取治理 Snapshot 失败，本地会话继续可用：{}", error.toString());
            }
        }
        harnessPool.bootstrap(initial);
        GovernanceToolMount governanceMount = harnessPool.currentMount();
        if (governance != null && (governanceMount == null || governanceMount.snapshotId() == null
                || governanceMount.registeredNames().isEmpty())) {
            logger.warn(
                    "治理客户端已配置，但 BoundTool 未挂载（snapshotId={} tools={}）；Demo 仅有本地工具",
                    governanceMount == null ? null : governanceMount.snapshotId(),
                    governanceMount == null ? List.of() : governanceMount.registeredNames());
        } else if (governanceMount != null && !governanceMount.registeredNames().isEmpty()) {
            logger.info(
                    "治理 BoundTool 已挂载 snapshotId={} tools={}",
                    governanceMount.snapshotId(),
                    governanceMount.registeredNames());
        }
        catalog = new ThreadCatalog(this::list, this::log, displayIndex);
        displayViews = new DisplayViewStore(displayIndex);
        // 启动时恢复已落盘会话；目录按需从两份日志派生。
        List<String> existing = list();
        existing.forEach(id -> session(id).start());
        projection = new ProjectionService(this::log, readModelPersistence);
        if (governance != null) {
            governanceRefreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "governance-snapshot-refresh");
                thread.setDaemon(true);
                return thread;
            });
            governanceRefreshExecutor.scheduleWithFixedDelay(
                    this::safeRefreshGovernanceAssembly,
                    GOVERNANCE_POLL_SECONDS,
                    GOVERNANCE_POLL_SECONDS,
                    TimeUnit.SECONDS);
        } else {
            governanceRefreshExecutor = null;
        }
    }

    private HarnessAgent buildHarness(PreparedGovernanceAssembly assembly) {
        Toolkit toolkit;
        boolean governanceToolsMounted = false;
        if (assembly != null) {
            toolkit = assembly.toolkit();
            governanceToolsMounted = assembly.mount() != null
                    && assembly.mount().snapshotId() != null
                    && !assembly.mount().registeredNames().isEmpty();
        } else {
            toolkit = new Toolkit();
            toolkit.registerTool(new OrderTools());
            toolkit.registerTool(new DocSearchTools());
            toolkit.registerTool(new NoteTools(workspace));
        }
        var builder = HarnessAgent.builder()
                .name("Session Chat")
                .agentId(AGENT)
                .model(model)
                .sysPrompt(systemPrompt(governanceToolsMounted))
                .workspace(workspace)
                .sessionLogStore(store)
                .toolkit(toolkit)
                .permissionContext(approvalPermissions())
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDefaultWorkspaceSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableToolsConfig()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .maxIters(8);
        if (assembly != null && assembly.skillRepository() != null) {
            builder.skillRepository(assembly.skillRepository());
        }
        return builder.build();
    }

    /**
     * 低频补齐 / 提交前：条件拉取并准备新装配供后续新 run 使用。
     *
     * <p>不再要求全局空闲；进行中任务继续挂在旧一代 Harness。提交与本方法共用 {@link #agentLock}。
     */
    public void refreshGovernanceAssembly() {
        if (governance == null) return;
        synchronized (agentLock) {
            refreshGovernanceAssemblyUnlocked();
        }
    }

    private void refreshGovernanceAssemblyUnlocked() {
        if (governance == null) return;
        try {
            String validator = adoptedEtag.get();
            GovernanceSnapshotFetch fetch = governance.fetchCurrent(validator);
            lastRefreshAt.set(Instant.now());
            if (fetch.notModified()) {
                lastRefreshFailure.set(null);
                logger.debug("治理装配条件读取 304 notModified validator={}", validator);
                return;
            }
            if (fetch.snapshot() == null) {
                throw new IllegalStateException("条件读取返回空 Snapshot");
            }
            PreparedGovernanceAssembly prepared = GovernanceAssemblySupport.prepare(
                    workspace, governance, fetch.snapshot(), fetch.etag());
            // 先准备成功再更新校验值与 current，避免装配失败后误 304。
            harnessPool.adopt(prepared);
            adoptedEtag.set(fetch.etag());
            lastRefreshFailure.set(null);
        } catch (RuntimeException error) {
            lastRefreshFailure.set(error.toString());
            logger.warn("刷新治理装配失败，继续使用已成功装配：{}", error.toString());
        }
    }

    private void safeRefreshGovernanceAssembly() {
        try {
            refreshGovernanceAssembly();
        } catch (RuntimeException error) {
            logger.warn("定时刷新治理装配异常：{}", error.toString());
        }
    }

    public GovernanceRuntimeStatus governanceRuntimeStatus() {
        return new GovernanceRuntimeStatus(
                governanceSnapshotId(),
                adoptedEtag.get(),
                lastRefreshAt.get(),
                lastRefreshFailure.get(),
                governance != null && governanceRefreshExecutor != null);
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
                .addAllowRule("load_skill_through_path", new PermissionRule(
                        "load_skill_through_path", null, PermissionBehavior.ALLOW, "治理 Skill 只读资源"))
                .build();
    }

    public String modelName() {
        return modelName;
    }

    /**
     * 未配置治理时保持原提示词。配置了治理地址后补充人员查询的确认和失败说明；
     * 工具是否真的出现仍由 Snapshot 决定，闲聊不因此强制调用工具。
     */
    public static String systemPrompt(boolean governanceTools) {
        return systemPrompt(governanceTools, "");
    }

    /**
     * @param skillsSection 治理 Snapshot skills[] 解析后的提示词片段；空白则不追加
     */
    public static String systemPrompt(boolean governanceTools, String skillsSection) {
        String base = "你是一个简洁的聊天助手，始终用与用户相同的语言回复。"
                + "需要查订单、检索文档或记录笔记时，优先调用已提供的工具，"
                + "不要编造工具返回的数据。";
        String prompt = governanceTools
                ? base
                        + "如果提供了治理查询工具，查询人员或身份时优先调用它，不要编造人员信息。"
                        + "出现多名候选人时先向用户确认；没有找到时如实说明。"
                        + "工具被拒绝、绑定失效、结果不确定或调用失败时，说明这次调用没有成功，不要编造结果，也不要自动反复重试。"
                        + "普通闲聊不要调用工具。"
                : base;
        if (skillsSection != null && !skillsSection.isBlank()) {
            prompt = prompt + skillsSection;
        }
        return prompt;
    }
    /** 当前可供新 run 使用的治理 Snapshot id；未装配则为 null。 */
    public String governanceSnapshotId() {
        return harnessPool.currentSnapshotId();
    }

    /** 当前新 run 装配中的治理 BoundTool 注册名；未挂载时为空列表。 */
    public List<String> governanceRegisteredTools() {
        GovernanceToolMount mount = harnessPool.currentMount();
        return mount == null ? List.of() : mount.registeredNames();
    }

    /** 会话当前持久的装配绑定 snapshotId（供恢复与回归断言）。 */
    public String boundAssemblySnapshotId(String sessionId) {
        return assemblyBindings.snapshotId(sessionKey(id(sessionId)));
    }

    /** 仍存活（未关闭）的 Harness 代 snapshotId 列表。 */
    public List<String> liveHarnessSnapshotIds() {
        return harnessPool.liveSnapshotIds();
    }

    /** 指定 snapshot 对应的一代是否已关闭。 */
    public boolean harnessGenerationClosed(String snapshotId) {
        return harnessPool.isGenerationClosed(snapshotId);
    }

    /** 会话当前 Harness 代上治理工具钉住的 snapshotId；无该工具时返回 null。 */
    public String boundToolSnapshotId(String sessionId, String toolName) {
        HarnessGenerationPool.Generation gen = harnessPool.generationForSession(sessionKey(id(sessionId)));
        if (gen == null || gen.agent() == null || toolName == null) return null;
        Object tool = gen.agent().getToolkit().getTool(toolName);
        if (tool instanceof GovernanceBoundTool bound) return bound.snapshotId();
        return null;
    }

    /**
     * 测试/诊断：模拟过期收口回调，仅当绑定仍是 {@code expectedSnapshotId} 时才清除。
     *
     * @return 是否实际清除了持久绑定
     */
    public boolean tryClearAssemblyBinding(String sessionId, String expectedSnapshotId) {
        String key = sessionKey(id(sessionId));
        synchronized (agentLock) {
            HarnessGenerationPool.Generation expected = harnessPool.peekSessionBinding(key);
            if (expected != null
                    && expectedSnapshotId != null
                    && !expectedSnapshotId.equals(expected.snapshotId())) {
                expected = null; // 调用方声称的版本与内存绑定不一致时，只做持久层 clearIfMatch
            }
            if (expected != null && expectedSnapshotId != null && expectedSnapshotId.equals(expected.snapshotId())) {
                harnessPool.onTaskSettled(key, expected, this::sessionBusyOnGeneration);
            }
            return assemblyBindings.clearIfMatch(key, expectedSnapshotId);
        }
    }

    private String sessionKey(String sessionId) {
        return activeUserId() + "\0" + id(sessionId);
    }

    private boolean sessionBusyOnGeneration(HarnessGenerationPool.Generation generation, String key) {
        if (harnessPool.hasEnqueueHold(key)) return true;
        int sep = key.indexOf('\0');
        String userId = sep < 0 ? activeUserId() : key.substring(0, sep);
        String sessionId = sep < 0 ? key : key.substring(sep + 1);
        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(id(sessionId)).build();
        try {
            AgentSession agentSession = generation.session(ctx);
            if (agentSession.pending() != null && !agentSession.pending().isEmpty()) return true;
        } catch (RuntimeException ignored) {
            // 尚未 start
        }
        AgentSession.Task pending = pendingTaskFromStore(userId, sessionId);
        if (pending == null) return false;
        String status = pending.status();
        return "queued".equals(status) || "running".equals(status);
    }

    private AgentSession.Task pendingTaskFromStore(String userId, String sessionId) {
        try {
            var key = new io.agentscope.core.session.SessionKey(userId, AGENT, id(sessionId));
            SessionLog log = store.open(
                    key, RuntimeContext.builder().userId(userId).sessionId(id(sessionId)).build());
            SessionLog inbox = log.inbox();
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
                var head = log.head();
                boolean active = head.owner() != null && head.leaseUntil() > System.currentTimeMillis();
                return new AgentSession.Task(
                        last.id(),
                        last.turnId(),
                        openedRun.get(last.turnId()),
                        active ? "running" : "interrupted",
                        null);
            }
            return null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 是否已挂载至少一个治理 BoundTool（供诊断与 /info 探测）。 */
    public boolean governanceBoundToolsMounted() {
        return governanceSnapshotId() != null && !governanceRegisteredTools().isEmpty();
    }

    /** 校验会话 id 合法：字母数字、下划线、连字符，1~80 位。 */
    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("非法的会话 ID");
        return value;
    }

    private String activeUserId() {
        return UserContext.current().orElse(defaultUserId);
    }

    /** 构造框架运行上下文：userId 取当前请求用户，sessionId 原样使用会话 id。 */
    private RuntimeContext context(String session) {
        return RuntimeContext.builder().userId(activeUserId()).sessionId(id(session)).build();
    }

    /**
     * 若会话已登记到其他用户则拒绝；未登记的新会话允许当前用户首次写入时认领。
     */
    public void assertAccessible(String sessionId) {
        SessionAccess.assertOwnedOrNew(agentSessions, AGENT, id(sessionId), activeUserId());
    }

    public SessionLog log(String session) {
        assertAccessible(session);
        return session(session).log();
    }

    AgentSession session(String id) {
        assertAccessible(id);
        String key = sessionKey(id);
        // 审批恢复：若持久绑定指向历史 Snapshot，确保该代 Harness 仍可用。
        String boundSnap = assemblyBindings.snapshotId(key);
        if (boundSnap != null && governance != null) {
            try {
                harnessPool.requireGeneration(boundSnap, this::rebuildAssembly);
            } catch (HarnessGenerationPool.AssemblyRecoveryException error) {
                throw new IllegalStateException(
                        "无法恢复原 Snapshot 绑定 snapshotId=" + boundSnap + "；请结束任务或重新发起", error);
            } catch (RuntimeException error) {
                throw new IllegalStateException(
                        "恢复原 Snapshot 装配失败 snapshotId=" + boundSnap + "：" + error.getMessage(), error);
            }
        }
        HarnessGenerationPool.Generation gen = harnessPool.generationForSession(key);
        return gen.session(context(id));
    }

    private PreparedGovernanceAssembly rebuildAssembly(String snapshotId) {
        GovernanceSnapshot snapshot = governance.fetchSnapshot(snapshotId);
        return GovernanceAssemblySupport.prepare(workspace, governance, snapshot, snapshotId);
    }

    /** 列出本 agent 下所有已落盘的会话 id。 */
    public List<String> list() {
        return store.list(RuntimeContext.builder().userId(activeUserId()).build()).stream()
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
    /** 等待阶段取消；仅作用于指定 turn，不删除会话历史。取消后清理对应 Run 凭证。 */
    public Map<String, String> cancelPending(String id, String turnId) {
        String sessionId = id(id);
        String runId = null;
        try {
            AgentSession.Task pending = pendingTask(sessionId);
            if (pending != null && turnId != null && turnId.equals(pending.turnId())) {
                runId = pending.runId();
                RunCredentialRegistry.clearCommand(pending.commandId(), activeUserId(), sessionId);
            }
        } catch (RuntimeException ignored) {
            // 清理失败不阻断取消主路径
        }
        Map<String, String> result = hitl.cancel(session(sessionId), turnId);
        if (runId != null) RunCredentialRegistry.clearRun(activeUserId(), sessionId, runId);
        return result;
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
        String userId = activeUserId();
        String sessionId = id(session);
        String request = id(requestId);
        String key = sessionKey(sessionId);
        AgentSession agentSession;
        HarnessGenerationPool.Generation gen;
        synchronized (agentLock) {
            // 与轮询刷新共用锁：任务绑定恰好一份已准备完成的装配。
            refreshGovernanceAssemblyUnlocked();
            assertAccessible(sessionId);
            gen = harnessPool.bindForSubmit(key, this::sessionBusyOnGeneration);
            agentSession = gen.session(context(sessionId));
            agentSession.start();
            if (gen.snapshotId() != null) {
                assemblyBindings.bind(key, gen.snapshotId());
            }
        }
        // 必须在 Harness submit（后台可能立刻跑工具）之前绑定 command 凭证；禁止 submit 后再 bind。
        String rawToken = SubmitCredentialCapture.current().orElse(null);
        if (rawToken != null && !rawToken.isBlank()) {
            RunCredentialRegistry.bindCommand(request, userId, sessionId, rawToken);
        }
        AgentSession.Task task;
        try {
            task = agentSession.submit(request, text);
        } catch (RuntimeException error) {
            RunCredentialRegistry.clearCommand(request, userId, sessionId);
            synchronized (agentLock) {
                harnessPool.abandonEnqueue(key, this::sessionBusyOnGeneration);
            }
            throw error;
        }
        synchronized (agentLock) {
            harnessPool.confirmEnqueued(key);
        }
        try {
            if (rawToken != null && !rawToken.isBlank()) {
                // 若 Harness 已带回 runId，arm 内同步确认关联并移交；终态清理仍走后台监管。
                RunCredentialLifecycle.arm(agentSession, userId, sessionId, task);
            }
            armAssemblyRelease(agentSession, key, gen, userId, sessionId, task);
        } catch (RuntimeException error) {
            RunCredentialRegistry.clearCommand(task.commandId(), userId, sessionId);
            if (task.runId() != null) RunCredentialRegistry.clearRun(userId, sessionId, task.runId());
            throw error;
        }
        return task;
    }

    /**
     * 后台收口释放必须使用提交时捕获的会话键与版本；过期回调只能清自己的绑定。
     */
    private void armAssemblyRelease(
            AgentSession agentSession,
            String key,
            HarnessGenerationPool.Generation generation,
            String userId,
            String sessionId,
            AgentSession.Task task) {
        if (task == null || task.commandId() == null) return;
        String commandId = task.commandId();
        String snapshotId = generation == null ? null : generation.snapshotId();
        assemblyLifecycleExecutor.execute(() -> {
            try {
                long limit = System.currentTimeMillis() + 600_000L;
                while (System.currentTimeMillis() < limit) {
                    AgentSession.Task latest = agentSession.task(commandId);
                    if (latest != null && latest.settled()) break;
                    Thread.sleep(40L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // 收口探测失败时仍尝试释放，避免永久占用一代
            } finally {
                synchronized (agentLock) {
                    harnessPool.onTaskSettled(key, generation, this::sessionBusyOnGeneration);
                    if (!sessionBusyOnGeneration(generation, key)) {
                        assemblyBindings.clearIfMatch(key, snapshotId);
                    }
                }
            }
        });
    }

    /** Run 结束/失败后清理该 Run 凭证；不影响同会话其他活动 Run。 */
    public void clearRunCredential(String sessionId, String runId) {
        if (runId == null || runId.isBlank()) return;
        RunCredentialRegistry.clearRun(activeUserId(), id(sessionId), runId);
    }

    /** 提交失败或命令被拒时清理尚未移交的 command 凭证。 */
    public void clearCommandCredential(String sessionId, String commandId) {
        RunCredentialRegistry.clearCommand(commandId, activeUserId(), id(sessionId));
    }

    /** @deprecated 使用 {@link #clearCommandCredential(String, String)}。 */
    @Deprecated
    public void clearCommandCredential(String commandId) {
        RunCredentialRegistry.clearCommand(commandId);
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
        if (governanceRefreshExecutor != null) {
            governanceRefreshExecutor.shutdownNow();
        }
        assemblyLifecycleExecutor.shutdownNow();
        projection.close();
        harnessPool.close();
    }
}
