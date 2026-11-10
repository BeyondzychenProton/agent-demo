package com.beyondzy.demo.governance;

import io.agentscope.harness.agent.session.AgentSession;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将用户凭证生命周期接到 Harness 后台执行，不依赖 SSE / 页面轮询。
 *
 * <p>框架 {@link AgentSession} 无公开 run 启停钩子；本监管在 submit 后轮询同一
 * {@code commandId} 的 Task：出现 runId 即 command→run 移交，收口（完成/失败/取消/拒绝）
 * 后按 Run 清理。页面断开只停观察，不影响本监管。
 *
 * <p>进程重启后内存表不恢复 Token；暂停/恢复若无重新移交则 fail-closed。
 */
public final class RunCredentialLifecycle {
    private static final Logger logger = LoggerFactory.getLogger(RunCredentialLifecycle.class);
    private static final long POLL_MS = 20L;
    private static final long HANDOFF_TIMEOUT_MS = 120_000L;
    private static final long SETTLE_TIMEOUT_MS = 600_000L;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
    private static final ThreadFactory THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "run-cred-lifecycle-" + THREAD_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(THREAD_FACTORY);

    private RunCredentialLifecycle() {}

    /**
     * submit 成功且已 bindCommand 后调用：若已有 runId 则同步移交，并启动后台监管直至收口清理。
     */
    public static void arm(AgentSession session, String userId, String sessionId, AgentSession.Task task) {
        if (session == null || task == null || task.commandId() == null || task.commandId().isBlank()) {
            return;
        }
        if (task.runId() != null && !task.runId().isBlank()) {
            // Harness 已给出 command→run：先确认关联再移交，供工具线程 fail-closed 定向移交。
            RunCredentialRegistry.confirmAssociation(
                    task.commandId(), userId, sessionId, task.runId());
            RunCredentialRegistry.transferCommandToRun(
                    task.commandId(), userId, sessionId, task.runId());
        }
        watch(session, userId, sessionId, task.commandId());
    }

    /**
     * 在后台跟踪一条已提交命令的凭证移交与释放。
     *
     * <p>调用方应已 {@link RunCredentialRegistry#bindCommand}；若 submit 已带回 runId，
     * 可先同步移交，本方法仍负责终态清理。
     */
    public static void watch(AgentSession session, String userId, String sessionId, String commandId) {
        if (session == null || commandId == null || commandId.isBlank()) return;
        EXECUTOR.execute(() -> supervise(session, userId, sessionId, commandId));
    }

    private static void supervise(AgentSession session, String userId, String sessionId, String commandId) {
        String runId = null;
        try {
            runId = awaitRunId(session, userId, sessionId, commandId);
            if (runId == null) return;
            awaitSettled(session, commandId);
        } catch (RuntimeException error) {
            logger.warn(
                    "凭证生命周期监管异常 command={} type={}",
                    commandId,
                    error.getClass().getSimpleName());
        } finally {
            release(userId, sessionId, commandId, runId);
        }
    }

    /** @return 已关联的 runId；命令已拒绝/收口/超时则返回 null（finally 仍清 command） */
    private static String awaitRunId(
            AgentSession session, String userId, String sessionId, String commandId) {
        long deadline = System.nanoTime() + HANDOFF_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            AgentSession.Task task = safeTask(session, commandId);
            if (task == null) return null;
            if ("rejected".equals(task.status())) return null;
            if (task.runId() != null && !task.runId().isBlank()) {
                RunCredentialRegistry.confirmAssociation(
                        task.commandId(), userId, sessionId, task.runId());
                RunCredentialRegistry.transferCommandToRun(
                        task.commandId(), userId, sessionId, task.runId());
                return task.runId();
            }
            if (task.settled()) return null;
            sleep(POLL_MS);
        }
        logger.warn("凭证移交等待 runId 超时，失败闭合清理 command={}", commandId);
        return null;
    }

    private static void awaitSettled(AgentSession session, String commandId) {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            AgentSession.Task task = safeTask(session, commandId);
            if (task == null || task.settled() || "rejected".equals(task.status())) return;
            sleep(POLL_MS);
        }
        logger.warn("凭证生命周期等待收口超时，失败闭合清理 command={}", commandId);
    }

    private static AgentSession.Task safeTask(AgentSession session, String commandId) {
        try {
            return session.task(commandId);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static void release(String userId, String sessionId, String commandId, String runId) {
        if (runId != null && !runId.isBlank()) {
            RunCredentialRegistry.clearRun(userId, sessionId, runId);
        }
        RunCredentialRegistry.clearCommand(commandId, userId, sessionId);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
