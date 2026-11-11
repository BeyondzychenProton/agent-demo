package com.beyondzy.demo.governance;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 run 绑定的多代 Harness 池。
 *
 * <p>新任务绑定 {@link #current()} 装配；进行中/待审批任务继续挂在提交时的一代上。
 * 旧 Harness 仅在引用归零后关闭。提交与换代共用外部传入的锁，避免「刷新与提交」竞态。
 *
 * <p><b>排队语义：</b>绑定发生在 submit 时刻。同一会话若仍有未收口任务，后续排队任务继续使用
 * 该会话已绑定的一代，不会中途跳到更新的 current。
 */
public final class HarnessGenerationPool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HarnessGenerationPool.class);

    private final Function<PreparedGovernanceAssembly, HarnessAgent> factory;
    private final Object lock;
    private volatile Generation current;
    private final ConcurrentHashMap<String, Generation> sessionBinding = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Generation> snapshotIndex = new ConcurrentHashMap<>();
    /**
     * 已选代但尚未成功入队的会话（引用计数）。
     * 同会话并发 submit 各自占一次 hold，全部入队或放弃后才允许后台收口释放。
     */
    private final ConcurrentHashMap<String, Integer> enqueueHoldCounts = new ConcurrentHashMap<>();

    public HarnessGenerationPool(Function<PreparedGovernanceAssembly, HarnessAgent> factory, Object lock) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.lock = lock == null ? this : lock;
    }

    /** 冷启动：用首份装配（可为 null，表示仅本地工具）建立 current。 */
    public Generation bootstrap(PreparedGovernanceAssembly assembly) {
        synchronized (lock) {
            Generation gen = create(assembly, false);
            current = gen;
            return gen;
        }
    }

    public Generation current() {
        return current;
    }

    public HarnessAgent currentAgent() {
        Generation gen = current;
        if (gen == null) throw new IllegalStateException("Harness 尚未初始化");
        return gen.agent();
    }

    public GovernanceToolMount currentMount() {
        Generation gen = current;
        return gen == null || gen.assembly() == null ? null : gen.assembly().mount();
    }

    public String currentSnapshotId() {
        Generation gen = current;
        return gen == null ? null : gen.snapshotId();
    }

    public String currentEtag() {
        Generation gen = current;
        return gen == null || gen.assembly() == null ? null : gen.assembly().etag();
    }

    /**
     * 采用一份已成功准备的新装配供后续新 run 使用。不要求全局空闲。
     *
     * @return 是否实际切换了 current
     */
    public boolean adopt(PreparedGovernanceAssembly prepared) {
        Objects.requireNonNull(prepared, "prepared");
        synchronized (lock) {
            Generation existing = current;
            if (existing != null
                    && existing.snapshotId() != null
                    && existing.snapshotId().equals(prepared.snapshotId())) {
                return false;
            }
            Generation next = create(prepared, false);
            Generation previous = current;
            current = next;
            if (previous != null) {
                previous.retire();
                tryClose(previous);
            }
            logger.info(
                    "治理装配已供新 run 使用 snapshotId={} previous={}",
                    next.snapshotId(),
                    previous == null ? null : previous.snapshotId());
            return true;
        }
    }

    /**
     * 为一次 submit 解析应使用的一代：会话仍忙则沿用绑定；否则绑定 current（调用方应先 adopt/refresh）。
     *
     * <p>返回前会登记 enqueue hold：从选代到成功入队期间，后台收口不得释放该会话绑定或关闭该代。
     * 调用方必须在入队成功后 {@link #confirmEnqueued}，或失败时 {@link #abandonEnqueue}。
     */
    public Generation bindForSubmit(String sessionKey, SessionBusyCheck busyCheck) {
        Objects.requireNonNull(sessionKey, "sessionKey");
        synchronized (lock) {
            Generation bound = sessionBinding.get(sessionKey);
            if (bound != null
                    && !bound.closed()
                    && (busyCheck.isBusy(bound, sessionKey) || hasEnqueueHold(sessionKey))) {
                acquireEnqueueHold(sessionKey);
                return bound;
            }
            Generation gen = current;
            if (gen == null) throw new IllegalStateException("Harness 尚未初始化");
            if (bound != gen) {
                sessionBinding.put(sessionKey, gen);
                gen.retain(sessionKey);
                if (bound != null) {
                    bound.release(sessionKey);
                    tryClose(bound);
                }
            }
            acquireEnqueueHold(sessionKey);
            return gen;
        }
    }

    /** 任务已成功入队：解除一次 enqueue hold，会话绑定仍由任务生命周期持有。 */
    public void confirmEnqueued(String sessionKey) {
        Objects.requireNonNull(sessionKey, "sessionKey");
        synchronized (lock) {
            releaseEnqueueHold(sessionKey);
        }
    }

    /** 入队失败：解除一次 enqueue hold，并在会话不再忙时释放绑定。 */
    public void abandonEnqueue(String sessionKey, SessionBusyCheck busyCheck) {
        Objects.requireNonNull(sessionKey, "sessionKey");
        synchronized (lock) {
            releaseEnqueueHold(sessionKey);
            Generation bound = sessionBinding.get(sessionKey);
            onTaskSettledUnlocked(sessionKey, bound, busyCheck);
        }
    }

    public boolean hasEnqueueHold(String sessionKey) {
        if (sessionKey == null) return false;
        Integer count = enqueueHoldCounts.get(sessionKey);
        return count != null && count > 0;
    }

    private void acquireEnqueueHold(String sessionKey) {
        enqueueHoldCounts.merge(sessionKey, 1, Integer::sum);
    }

    private void releaseEnqueueHold(String sessionKey) {
        enqueueHoldCounts.compute(sessionKey, (k, v) -> {
            if (v == null || v <= 1) return null;
            return v - 1;
        });
    }

    /** 读路径 / 审批恢复：优先会话已绑定的一代，否则 current。 */
    public Generation generationForSession(String sessionKey) {
        Generation bound = sessionBinding.get(sessionKey);
        if (bound != null && !bound.closed()) return bound;
        Generation gen = current;
        if (gen == null) throw new IllegalStateException("Harness 尚未初始化");
        return gen;
    }

    /** 按历史 snapshotId 取得或重建一代（进程恢复审批用）。 */
    public Generation requireGeneration(
            String snapshotId, Function<String, PreparedGovernanceAssembly> rebuild) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        synchronized (lock) {
            Generation hit = snapshotIndex.get(snapshotId);
            if (hit != null && !hit.closed()) return hit;
            if (current != null && snapshotId.equals(current.snapshotId()) && !current.closed()) {
                return current;
            }
            PreparedGovernanceAssembly prepared = rebuild.apply(snapshotId);
            if (prepared == null) {
                throw new AssemblyRecoveryException("无法恢复 Snapshot 绑定: " + snapshotId);
            }
            return create(prepared, true);
        }
    }

    public void releaseSession(String sessionKey) {
        synchronized (lock) {
            enqueueHoldCounts.remove(sessionKey);
            Generation bound = sessionBinding.remove(sessionKey);
            if (bound == null) return;
            bound.release(sessionKey);
            tryClose(bound);
        }
    }

    /**
     * 任务收口后：若会话不再忙且仍绑定在 {@code expected} 这一代，则释放。
     * 过期回调在会话已换绑到其他代时不得清掉新绑定。
     */
    public void onTaskSettled(String sessionKey, Generation expected, SessionBusyCheck busyCheck) {
        synchronized (lock) {
            onTaskSettledUnlocked(sessionKey, expected, busyCheck);
        }
    }

    /** @deprecated 使用 {@link #onTaskSettled(String, Generation, SessionBusyCheck)} */
    @Deprecated
    public void onTaskSettled(String sessionKey, SessionBusyCheck busyCheck) {
        onTaskSettled(sessionKey, null, busyCheck);
    }

    private void onTaskSettledUnlocked(String sessionKey, Generation expected, SessionBusyCheck busyCheck) {
        if (hasEnqueueHold(sessionKey)) return;
        Generation bound = sessionBinding.get(sessionKey);
        if (bound == null) return;
        if (expected != null && bound != expected) return;
        if (busyCheck != null && busyCheck.isBusy(bound, sessionKey)) return;
        if (!sessionBinding.remove(sessionKey, bound)) return;
        bound.release(sessionKey);
        tryClose(bound);
    }

    public List<String> liveSnapshotIds() {
        List<String> ids = new ArrayList<>();
        Generation cur = current;
        if (cur != null && cur.snapshotId() != null && !cur.closed()) ids.add(cur.snapshotId());
        for (Generation gen : snapshotIndex.values()) {
            if (gen.snapshotId() != null && !gen.closed() && !ids.contains(gen.snapshotId())) {
                ids.add(gen.snapshotId());
            }
        }
        return ids;
    }

    public boolean isGenerationClosed(String snapshotId) {
        if (snapshotId == null) return true;
        Generation hit = snapshotIndex.get(snapshotId);
        if (hit != null) return hit.closed();
        Generation cur = current;
        if (cur != null && snapshotId.equals(cur.snapshotId())) return cur.closed();
        return true;
    }

    public Generation peekSessionBinding(String sessionKey) {
        return sessionKey == null ? null : sessionBinding.get(sessionKey);
    }

    @Override
    public void close() {
        synchronized (lock) {
            enqueueHoldCounts.clear();
            sessionBinding.clear();
            List<Generation> all = new ArrayList<>(snapshotIndex.values());
            for (Generation gen : all) {
                gen.retire();
                forceClose(gen);
            }
            current = null;
        }
    }

    private Generation create(PreparedGovernanceAssembly assembly, boolean retired) {
        HarnessAgent agent = factory.apply(assembly);
        Generation gen = new Generation(agent, assembly);
        if (retired) gen.retire();
        if (gen.snapshotId() != null) {
            snapshotIndex.put(gen.snapshotId(), gen);
        }
        return gen;
    }

    private void tryClose(Generation gen) {
        if (gen == null) return;
        if (gen == current) return;
        if (!gen.retired()) return;
        if (gen.retainCount() > 0) return;
        forceClose(gen);
    }

    private void forceClose(Generation gen) {
        if (gen.closed()) return;
        gen.markClosed();
        if (gen.snapshotId() != null) {
            snapshotIndex.remove(gen.snapshotId(), gen);
        }
        try {
            gen.agent().close();
        } catch (RuntimeException error) {
            logger.warn("关闭旧 Harness 失败 snapshotId={}: {}", gen.snapshotId(), error.toString());
        }
    }

    @FunctionalInterface
    public interface SessionBusyCheck {
        boolean isBusy(Generation generation, String sessionKey);
    }

    public static final class Generation {
        private final HarnessAgent agent;
        private final PreparedGovernanceAssembly assembly;
        private final AtomicInteger retains = new AtomicInteger();
        private volatile boolean retired;
        private volatile boolean closed;

        private Generation(HarnessAgent agent, PreparedGovernanceAssembly assembly) {
            this.agent = agent;
            this.assembly = assembly;
        }

        public HarnessAgent agent() {
            return agent;
        }

        public PreparedGovernanceAssembly assembly() {
            return assembly;
        }

        public String snapshotId() {
            return assembly == null ? null : assembly.snapshotId();
        }

        public AgentSession session(io.agentscope.core.agent.RuntimeContext context) {
            return agent.session(context);
        }

        void retain(String sessionKey) {
            retains.incrementAndGet();
        }

        void release(String sessionKey) {
            retains.decrementAndGet();
        }

        int retainCount() {
            return retains.get();
        }

        void retire() {
            retired = true;
        }

        boolean retired() {
            return retired;
        }

        void markClosed() {
            closed = true;
        }

        boolean closed() {
            return closed;
        }
    }

    /** 历史 Snapshot 无法重建时抛出，供宿主转为明确失败状态。 */
    public static final class AssemblyRecoveryException extends IllegalStateException {
        public AssemblyRecoveryException(String message) {
            super(message);
        }
    }
}
