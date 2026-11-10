package com.beyondzy.demo.governance;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将提交任务时捕获的 rawSessionToken 绑定到 command 复合键，并在 Run 启动时移交到 run 作用域。
 *
 * <p>命令索引必须是 userId/sessionId/commandId 复合键：客户端可指定 request/commandId，
 * 不能假设全局唯一。解析只认 Run 绑定；禁止按「同会话最早 pending」猜测移交。
 * 仅当 Harness 已确认 command→run 关联（{@link #confirmAssociation} /
 * {@link #transferCommandToRun}）时才允许移交；缺少关联时失败闭合。
 */
public final class RunCredentialRegistry {
    private static final ConcurrentHashMap<String, String> BY_COMMAND = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> BY_RUN = new ConcurrentHashMap<>();
    /** Harness 确认的 runKey → commandKey；仅权威关联可移交。 */
    private static final ConcurrentHashMap<String, String> COMMAND_BY_RUN = new ConcurrentHashMap<>();

    private RunCredentialRegistry() {}

    public static void bindCommand(String commandId, String userId, String sessionId, String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) return;
        String commandKey = commandKey(userId, sessionId, commandId);
        if (commandKey == null) return;
        BY_COMMAND.put(commandKey, rawSessionToken);
    }

    public static void bindRun(String userId, String sessionId, String runId, String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) return;
        String runKey = runKey(userId, sessionId, runId);
        if (runKey != null) BY_RUN.put(runKey, rawSessionToken);
    }

    /**
     * 记录 Harness 确认的 command→run 关联（不移动凭证）。
     * 工具线程可据此定向移交，禁止借用其它 command。
     */
    public static boolean confirmAssociation(String commandId, String userId, String sessionId, String runId) {
        String commandKey = commandKey(userId, sessionId, commandId);
        String run = runKey(userId, sessionId, runId);
        if (commandKey == null || run == null) return false;
        COMMAND_BY_RUN.put(run, commandKey);
        return true;
    }

    /**
     * command → run 显式移交：取出 command 凭证写入 Run 后清除 command 条目。
     *
     * @return false 表示 command 无绑定（fail-closed，不 invent / 不借会话）
     */
    public static boolean transferCommandToRun(String commandId, String userId, String sessionId, String runId) {
        String commandKey = commandKey(userId, sessionId, commandId);
        String run = runKey(userId, sessionId, runId);
        if (commandKey == null || run == null) return false;
        confirmAssociation(commandId, userId, sessionId, runId);
        String token = BY_COMMAND.remove(commandKey);
        if (token == null || token.isBlank()) return false;
        BY_RUN.put(run, token);
        return true;
    }

    /** 仅按 Run 查找；缺失时不回退会话或其他 Run。 */
    public static Optional<String> resolve(RuntimeContext context) {
        if (context == null) return Optional.empty();
        String run = runKey(context.getUserId(), context.getSessionId(), context.getRunId());
        if (run == null) return Optional.empty();
        return Optional.ofNullable(BY_RUN.get(run)).filter(token -> !token.isBlank());
    }

    /**
     * 工具执行前：若 Run 尚未绑定，仅在存在 Harness 确认的 command→run 关联时移交该 command。
     * 无关联则失败闭合——不得猜测同会话其它 pending command。
     */
    public static boolean ensureRunBinding(RuntimeContext context) {
        if (context == null) return false;
        if (resolve(context).isPresent()) return true;
        String run = runKey(context.getUserId(), context.getSessionId(), context.getRunId());
        if (run == null) return false;
        String commandKey = COMMAND_BY_RUN.get(run);
        if (commandKey == null) return false;
        String token = BY_COMMAND.remove(commandKey);
        if (token == null || token.isBlank()) return resolve(context).isPresent();
        BY_RUN.put(run, token);
        return true;
    }

    public static Optional<String> findByCommand(String commandId, String userId, String sessionId) {
        String commandKey = commandKey(userId, sessionId, commandId);
        if (commandKey == null) return Optional.empty();
        return Optional.ofNullable(BY_COMMAND.get(commandKey)).filter(token -> !token.isBlank());
    }

    /** @deprecated 使用 {@link #findByCommand(String, String, String)}；无用户/会话时无法唯一定位。 */
    @Deprecated
    public static Optional<String> findByCommand(String commandId) {
        if (commandId == null || commandId.isBlank()) return Optional.empty();
        String suffix = "|" + commandId.trim();
        for (var entry : BY_COMMAND.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                return Optional.ofNullable(entry.getValue()).filter(token -> !token.isBlank());
            }
        }
        return Optional.empty();
    }

    public static boolean hasRunBinding(String userId, String sessionId, String runId) {
        String run = runKey(userId, sessionId, runId);
        if (run == null) return false;
        String token = BY_RUN.get(run);
        return token != null && !token.isBlank();
    }

    public static boolean hasAssociation(String userId, String sessionId, String runId) {
        String run = runKey(userId, sessionId, runId);
        return run != null && COMMAND_BY_RUN.containsKey(run);
    }

    public static void clearCommand(String commandId, String userId, String sessionId) {
        String commandKey = commandKey(userId, sessionId, commandId);
        if (commandKey == null) return;
        BY_COMMAND.remove(commandKey);
        COMMAND_BY_RUN.entrySet().removeIf(entry -> commandKey.equals(entry.getValue()));
    }

    /** @deprecated 使用 {@link #clearCommand(String, String, String)}。 */
    @Deprecated
    public static void clearCommand(String commandId) {
        if (commandId == null || commandId.isBlank()) return;
        String suffix = "|" + commandId.trim();
        BY_COMMAND.entrySet().removeIf(entry -> entry.getKey().endsWith(suffix));
        COMMAND_BY_RUN.entrySet().removeIf(entry -> entry.getValue().endsWith(suffix));
    }

    public static void clearRun(String userId, String sessionId, String runId) {
        String run = runKey(userId, sessionId, runId);
        if (run == null) return;
        BY_RUN.remove(run);
        COMMAND_BY_RUN.remove(run);
    }

    public static void clearAll() {
        BY_COMMAND.clear();
        BY_RUN.clear();
        COMMAND_BY_RUN.clear();
    }

    static String commandKey(String userId, String sessionId, String commandId) {
        if (commandId == null || commandId.isBlank()) return null;
        String session = sessionKey(userId, sessionId);
        if (session == null) return null;
        return session + "|" + commandId.trim();
    }

    private static String sessionKey(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) return null;
        return userId.trim() + "|" + sessionId.trim();
    }

    private static String runKey(String userId, String sessionId, String runId) {
        if (runId == null || runId.isBlank()) return null;
        String session = sessionKey(userId, sessionId);
        if (session == null) return null;
        return session + "|" + runId.trim();
    }
}
