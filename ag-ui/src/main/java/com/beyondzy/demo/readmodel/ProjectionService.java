package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.contract.UserContext;
import com.beyondzy.demo.contract.ThreadView;
import com.beyondzy.demo.hitl.HitlService;
import com.beyondzy.demo.service.readmodel.ReadModelPersistenceService;
import com.beyondzy.demo.session.ChatSessions;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionLog;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 同一会话仅一个投影器读取日志，浏览器连接共享已提交的展示变更。 */
public final class ProjectionService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectionService.class);
    public record Control(List<SessionEvent> facts, List<SessionEvent> inputs, CommandProjection.Checkpoint commands) {}
    public record Status(String syncState, String activeRun, long execSeq, long inboxSeq,
                         long revision, int pendingCount, boolean busy, List<CommandProjection.Command> commands) {}
    private final java.util.function.Function<String, SessionLog> logs;
    private final ReadModelStore store;
    private final ScheduledExecutorService worker;
    /** 后台 tick 无 HTTP 请求，必须记住 watch 时的归属用户，否则会落到种子 userId。 */
    private record Watch(String userId, long at) {}
    private final Map<String, Watch> watched = new ConcurrentHashMap<>();
    private record Observation(SessionLog.Head head, long inboxSeq, long at) {}
    private final Map<String, Observation> observations = new ConcurrentHashMap<>();
    private record CachedCommands(long execSeq, long inboxSeq, boolean currentFormat,
                                  List<CommandProjection.Command> commands) {}
    private final Map<String, CachedCommands> commandViews = new ConcurrentHashMap<>();
    private final Map<String, Context> contexts = new LinkedHashMap<>(16, .75f, true);
    private long nextCleanup;

    private static class Context {
        ChatItems reducer;
        ReadModelStore.State state;
        final List<SessionEvent> facts = new ArrayList<>(), inputs = new ArrayList<>();
        final CommandProjection commands = new CommandProjection();
        long scans, events;
        long estimatedBytes;
    }

    public ProjectionService(java.util.function.Function<String, SessionLog> logs,
                             ReadModelPersistenceService persistence) {
        this.logs = logs;
        store = new ReadModelStore(persistence);
        worker = newWorker();
    }

    @Deprecated(forRemoval = true)
    public ProjectionService(ChatSessions sessions, Path workspace) {
        throw new UnsupportedOperationException("请注入 ReadModelPersistenceService");
    }

    @Deprecated(forRemoval = true)
    public ProjectionService(java.util.function.Function<String, SessionLog> logs, Path workspace) {
        throw new UnsupportedOperationException("请注入 ReadModelPersistenceService");
    }

    @Deprecated(forRemoval = true)
    public ProjectionService(java.util.function.Function<String, SessionLog> logs, DataSource dataSource) {
        throw new UnsupportedOperationException("请注入 ReadModelPersistenceService");
    }

    private ScheduledExecutorService newWorker() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "展示增量投影"); thread.setDaemon(true); return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
        return scheduler;
    }

    public ReadModelStore store() { return store; }

    /** 页面查询只查派生读库；首次升级尚无投影时只初始化一次。 */
    public void watch(String thread) {
        String userId = UserContext.current()
                .orElseThrow(() -> new IllegalStateException("投影 watch 需要 UserContext"));
        logs.apply(thread);
        watched.put(thread, new Watch(userId, System.currentTimeMillis()));
        if (store.state(thread) == null) sync(thread);
    }

    public synchronized void sync(String thread) {
        try { syncUnsafe(thread); }
        catch (RuntimeException error) {
            // 丢弃尚未提交的内存累加器，下次从事务水位继续，不能跳过失败批次。
            contexts.remove(thread);
            throw error;
        }
    }

    private void syncUnsafe(String thread) {
        SessionLog log = logs.apply(thread);
        var head = log.head(); long inputHead = log.inbox().head().seq();
        observations.put(thread, new Observation(head, inputHead, System.currentTimeMillis()));
        var persisted = store.state(thread);
        if (persisted != null && !commands(thread, persisted).currentFormat()) {
            // 旧展示检查点没有命令阶段，只重建派生索引，不把未知状态误判成可继续发送。
            store.invalidate(thread); contexts.remove(thread); commandViews.remove(thread); persisted = null;
        }
        String lease = leaseTag(head);
        // 空闲水位未变时甚至不解码累加器，避免缓存淘汰后轮询反复加载巨大检查点。
        if (persisted != null && persisted.execSeq() == head.seq() && persisted.inboxSeq() == inputHead
                && persisted.leaseTag().equals(lease)) return;
        var context = contexts.computeIfAbsent(thread, key -> load(key));
        while (contexts.size() > 32) {
            var first = contexts.keySet().iterator().next();
            if (first.equals(thread)) break;
            contexts.remove(first);
        }
        long after = context.state == null ? 0 : context.state.execSeq();
        long inputAfter = context.state == null ? 0 : context.state.inboxSeq();
        if (context.state != null && after == head.seq() && inputAfter == inputHead
                && lease.equals(context.state.leaseTag())) return;
        boolean interactionChanged = context.state == null || !lease.equals(context.state.leaseTag());
        if (after < head.seq()) {
            context.scans++;
            for (var fact : log.scan(after, head.seq())) {
                context.reducer.apply(fact); context.events++;
                context.commands.execution(fact);
                if (Set.of("interaction/requested", "interaction/resolved", "turn/cancelled", "inbox/started", "run/end").contains(fact.type())) {
                    context.facts.add(fact);
                    interactionChanged = true;
                }
            }
        }
        if (inputAfter < inputHead) {
            context.scans++;
            for (var input : log.inbox().scan(inputAfter, inputHead)) {
                context.commands.inbox(input);
                if (input.type().equals("inbox/accepted")) {
                    if (input.payload(SessionInbox.Command.class).kind().equals("respond")) context.inputs.add(input);
                } else if (input.type().equals("inbox/rejected")) context.inputs.add(input);
            }
            interactionChanged = true;
        }
        compactControl(context);
        List<ChatItems.Item> changed = context.reducer.drainChanges();
        var raw = new LinkedHashMap<String, ChatItems.Item>();
        changed.forEach(item -> raw.put(item.id(), item));
        var interactions = HitlService.projectForDisplay(context.facts, context.inputs, head);
        if (interactionChanged) for (var interaction : interactions.values()) {
            String id = "tool:" + interaction.turnId() + ":" + interaction.call().getId();
            if (!raw.containsKey(id)) {
                var saved = store.raw(thread, id);
                if (saved != null) raw.put(id, saved);
            }
        }
        var views = new LinkedHashMap<String, Object>();
        var orders = new LinkedHashMap<String, Long>();
        long nextOrder = store.maxOrder(thread);
        for (var item : raw.values()) {
            var view = ThreadViewAssembler.item(item);
            if (view == null) continue;
            var base = new ThreadView.SnapshotResponse(thread, List.of(view), List.of(), head.seq(), thread + ":" + head.seq(), null);
            view = new HitlService().decorate(base, interactions).view().get(0);
            views.put(item.id(), view);
            Long order = store.order(thread, item.id());
            orders.put(item.id(), order == null ? ++nextOrder : order);
        }
        context.reducer.compact();
        String checkpoint = ReadModelStore.json(context.reducer.checkpoint());
        var commandCheckpoint = context.commands.checkpoint();
        String control = ReadModelStore.json(new Control(context.facts, context.inputs, commandCheckpoint));
        store.commit(thread, context.state, head.seq(), inputHead, lease,
                checkpoint, control,
                changed, views, orders);
        context.state = store.state(thread);
        commandViews.put(thread, new CachedCommands(head.seq(), inputHead, true, commandCheckpoint.commands()));
        context.estimatedBytes = 2L * (checkpoint.length() + control.length()) + 65536;
        long cachedBytes = contexts.values().stream().mapToLong(cached -> cached.estimatedBytes).sum();
        var iterator = contexts.entrySet().iterator();
        while (cachedBytes > 128L * 1024 * 1024 && iterator.hasNext()) {
            var cached = iterator.next().getValue(); cachedBytes -= cached.estimatedBytes; iterator.remove();
        }
        LOG.debug("展示投影 会话={} 执行水位={} 输入水位={} 本批变化={} 累计扫描={} 累计事件={}",
                thread, head.seq(), inputHead, views.size(), context.scans, context.events);
    }

    private Context load(String thread) {
        var context = new Context(); context.state = store.state(thread);
        context.reducer = new ChatItems(id -> store.raw(thread, id));
        if (context.state != null) {
            try {
                context.reducer.restore(ReadModelStore.decode(context.state.checkpoint(), ChatItems.Checkpoint.class));
                var control = ReadModelStore.decode(context.state.control(), Control.class);
                if (control.commands() == null) throw new IllegalArgumentException("命令展示格式需要升级");
                context.commands.restore(control.commands());
                context.facts.addAll(control.facts()); context.inputs.addAll(control.inputs());
            } catch (RuntimeException invalidFormat) {
                LOG.info("展示累加器格式失效，重新投影会话：{}", thread);
                store.invalidate(thread);
                return load(thread);
            }
        }
        return context;
    }

    public Status status(String thread) {
        var state = store.state(thread); var observation = observations.get(thread);
        if (state == null) return new Status("loading", null, 0, 0, 0, 0, false, List.of());
        // 显示状态使用共享轮询结果；不能让每个页面和SSE连接再打开多次权威存储连接。
        var head = observation == null ? null : observation.head();
        boolean fresh = observation != null && System.currentTimeMillis() - observation.at() < 2000;
        var cached = commands(thread, state);
        var commands = cached.commands();
        int pending = store.pendingCount(thread);
        return new Status(cached.currentFormat() && fresh && state.execSeq() == head.seq() && state.inboxSeq() == observation.inboxSeq()
                && state.leaseTag().equals(leaseTag(head)) ? "ready" : "syncing",
                head != null && head.leaseUntil() > System.currentTimeMillis() ? head.owner() : null,
                state.execSeq(), state.inboxSeq(), state.revision(), pending,
                !cached.currentFormat() || pending > 0 || commands.stream().anyMatch(CommandProjection.Command::busy)
                        || (head != null && head.leaseUntil() > System.currentTimeMillis()),
                commands.subList(Math.max(0, commands.size() - 64), commands.size()));
    }

    private CachedCommands commands(String thread, ReadModelStore.State state) {
        var cached = commandViews.get(thread);
        if (cached != null && cached.execSeq() == state.execSeq() && cached.inboxSeq() == state.inboxSeq()) return cached;
        var control = ReadModelStore.decode(state.control(), Control.class);
        var value = new CachedCommands(state.execSeq(), state.inboxSeq(), control.commands() != null,
                control.commands() == null ? List.of() : control.commands().commands());
        commandViews.put(thread, value);
        return value;
    }

    public static String leaseTag(SessionLog.Head head) {
        return (head.owner() == null ? "" : head.owner()) + ":" + (head.leaseUntil() > System.currentTimeMillis());
    }

    /** 普通用户命令和无关 run 不属于审批投影状态，避免元数据随所有轮次重复增长。 */
    private static void compactControl(Context context) {
        var commandIds = new HashSet<String>();
        for (var event : context.inputs) if (event.type().equals("inbox/accepted"))
            commandIds.add(event.payload(SessionInbox.Command.class).id());
        var runs = new HashSet<String>();
        for (var event : context.facts) if (event.type().equals("inbox/started")
                && commandIds.contains(event.data().get("commandId"))) runs.add(event.executionRunId());
        context.facts.removeIf(event -> event.type().equals("inbox/started")
                ? !commandIds.contains(event.data().get("commandId"))
                : event.type().equals("run/end") && !runs.contains(event.executionRunId()));
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            for (var entry : watched.entrySet()) {
                Watch watch = entry.getValue();
                if (now - watch.at() > 60_000) {
                    watched.remove(entry.getKey(), watch);
                    observations.remove(entry.getKey());
                    commandViews.remove(entry.getKey());
                    continue;
                }
                try {
                    // 后台线程无 Security/Reactor 上下文；用 watch 登记的 userId 恢复归属校验。
                    UserContext.run(watch.userId(), () -> sync(entry.getKey()));
                } catch (RuntimeException error) {
                    LOG.warn("会话展示投影暂时失败：{}", entry.getKey(), error);
                }
            }
            if (now > nextCleanup) { store.cleanup(); nextCleanup = now + 60_000; }
        } catch (RuntimeException error) { LOG.warn("展示调度暂时失败", error); }
    }

    @Override public void close() {
        worker.shutdownNow();
        try { if (!worker.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("展示投影线程未停止"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        store.close();
    }
}
