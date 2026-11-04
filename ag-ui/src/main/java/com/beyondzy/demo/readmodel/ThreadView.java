package com.beyondzy.demo.readmodel;

import java.util.List;

/**
 * 前端视图对象与接口契约（对应大纲 6.1.7 / 6.1.8）。
 *
 * <p>这些对象由 SessionLog 投影而来，可作为派生读索引持久化；live 帧流与 snapshot 重建
 * 目标上都归一到 {@link ToolCardViewModel}。
 */
public final class ThreadView {

    private ThreadView() {}

    public interface ChatItem {
        String kind();
    }

    public record DiffStats(int added, int deleted) {}

    /**
     * 完整人工交互视图，处理完仍保留。
     *
     * @param requestId 工具确认请求编号，当前路径取原 toolCallId
     * @param askPrompt 本次操作的确认说明
     * @param payload 服务端保存的原始操作参数，只读展示
     * @param decision 已接收的选择；不能据此推断框架已处理或工具已成功
     * @param decideAt 已解决交互的事实时间，不使用浏览器点击时间
     * @param auditRef 原始请求事件引用，轨迹仍从日志读取
     * @param interactionStatus pending/processing/approved/rejected/cancelled/reconciling
     * @param reason 人提交的说明，参与重复答复比较
     * @param commandId Inbox 中的答复命令，接收后可查回
     * @param continuationRunId 命令启动后关联的新执行，尚未启动时为空
     * @param canRespond 页面当前是否可操作，提交时后端仍会重新校验
     * @param unavailableReason 不可操作的中文原因
     */
    public record HitlBlock(
            String requestId,
            String askPrompt,
            Object payload,
            String decision,
            String decideAt,
            String auditRef,
            String interactionStatus,
            String reason,
            String commandId,
            String continuationRunId,
            boolean canRespond,
            String unavailableReason) {
        /** 兼容旧事件投影，完整状态以联合查询为准。 */
        public HitlBlock(String id, String prompt, Object payload, String decision, String at, String audit) {
            this(id, prompt, payload, decision, at, audit, "pending", null, null, null, false, "正在核对交互状态");
        }
    }

    public record EvictionRef(String head, String tail, String readFilePointer) {}

    /** 消息项：文本/思考。partial=true 表示 committed 的半段前缀，由增量补全。 */
    public record MessageItem(
            String kind,
            String messageId,
            String turnId,
            String runId,
            String role,
            String text,
            Boolean partial)
            implements ChatItem {}

    /** 工具卡片统一 ViewModel：live 帧流、snapshot 重建、HITL 三条路径归一。 */
    public record ToolCardViewModel(
            String kind,
            String toolCallId,
            String turnId,
            String runId,
            String name,
            String displayTitle,
            String status,
            Object arguments,
            Object result,
            String error,
            String progress,
            DiffStats diffStats,
            EvictionRef evictionRef,
            HitlBlock hitl,
            String startedAt,
            String endedAt,
            Long durationMs)
            implements ChatItem {}

    /** 执行日志中的待答列表；是否可操作仍以工具卡片完整 hitl 视图为准。 */
    public record PendingInteraction(
            String requestId,
            String toolCallId,
            String turnId,
            String runId,
            String askPrompt,
            Object payload) {}

    public record ActiveRun(String runId, String status) {}

    public record SnapshotResponse(
            String threadId,
            List<ChatItem> view,
            List<PendingInteraction> pendingInteractions,
            long asOfSeq,
            String cursor,
            ActiveRun activeRun) {}

    /** 增量通道事件联合类型（大纲 6.1.8-D）。 */
    public sealed interface IncrementalEvent
            permits MessageDelta,
                    CardArgs,
                    CardStatus,
                    CardResult,
                    HitlPending,
                    HitlDecided,
                    RunFinished {}

    public record MessageDelta(
            String type,
            String messageId,
            String turnId,
            String runId,
            String role,
            String chunk,
            boolean partial)
            implements IncrementalEvent {}

    public record CardArgs(
            String type,
            String toolCallId,
            String turnId,
            String runId,
            String name,
            String displayTitle,
            Object argsChunk)
            implements IncrementalEvent {}

    public record CardStatus(String type, String toolCallId, String status, String progress)
            implements IncrementalEvent {}

    public record CardResult(
            String type,
            String toolCallId,
            String status,
            Object result,
            String error,
            String endedAt)
            implements IncrementalEvent {}

    public record HitlPending(
            String type,
            String requestId,
            String toolCallId,
            String askPrompt,
            Object payload)
            implements IncrementalEvent {}

    public record HitlDecided(
            String type, String requestId, String decision, String decideAt)
            implements IncrementalEvent {}

    public record RunFinished(String type, String runId, String status)
            implements IncrementalEvent {}
}
