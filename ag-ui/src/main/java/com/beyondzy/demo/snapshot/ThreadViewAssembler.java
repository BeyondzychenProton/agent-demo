package com.beyondzy.demo.snapshot;

import com.beyondzy.demo.contract.ThreadView;
import com.beyondzy.demo.support.ContentValues;
import com.beyondzy.demo.support.Payloads;
import com.beyondzy.demo.tools.ToolCatalog;
import io.agentscope.core.message.ToolUseBlock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M4 历史快照组装：把 {@link ChatItems} 的已提交事实聚合成 6.1.8 的页面契约。
 *
 * <p>组装结果允许持久化为可重建读索引。live 帧流与 snapshot 重建目标上产出同构对象。
 */
public final class ThreadViewAssembler {

    private ThreadViewAssembler() {
    }

    /**
     * 单项转换供增量投影使用，复用历史快照的消息及工具卡片规则。
     */
    public static ThreadView.ChatItem item(ChatItems.Item item) {
        return "tool".equals(item.kind()) ? toolCard(item) : message(item);
    }

    public static ThreadView.SnapshotResponse assemble(String threadId, ChatHistory.Snapshot snapshot) {
        List<ThreadView.ChatItem> view = new ArrayList<>();
        for (ChatItems.Item item : snapshot.items()) {
            if ("tool".equals(item.kind())) {
                view.add(toolCard(item));
            } else {
                ThreadView.MessageItem message = message(item);
                if (message != null) view.add(message);
            }
        }

        List<ThreadView.PendingInteraction> pending = new ArrayList<>();
        for (ChatHistory.Frame frame : snapshot.pending()) {
            pending.add(pending(frame));
        }

        ThreadView.ActiveRun activeRun =
                snapshot.activeRun() == null
                        ? null
                        : new ThreadView.ActiveRun(snapshot.activeRun(), "running");

        return new ThreadView.SnapshotResponse(
                threadId,
                List.copyOf(view),
                List.copyOf(pending),
                ChatHistory.position(threadId, snapshot.cursor()),
                snapshot.cursor(),
                activeRun);
    }

    private static ThreadView.MessageItem message(ChatItems.Item item) {
        String text = ContentValues.textOf(item.content());
        if (text.isBlank()) return null;
        return new ThreadView.MessageItem(
                "message",
                stripPrefix(item.id(), "message:"),
                item.turnId(),
                item.runId(),
                item.role(),
                text,
                !"completed".equals(item.status()));
    }

    private static ThreadView.ToolCardViewModel toolCard(ChatItems.Item item) {
        ToolUseBlock call = item.call();
        String name = call == null || call.getName() == null ? "unknown" : call.getName();

        Object arguments = call == null ? null : call.getInput();
        if ((arguments == null || (arguments instanceof Map<?, ?> map && map.isEmpty()))
                && !item.arguments().isBlank()) {
            arguments = ContentValues.parseJson(item.arguments());
        }
        Map<String, Object> argumentMap = ContentValues.asMap(arguments);

        String resultText = ContentValues.resultText(item.result());
        Object result = ContentValues.parseJson(resultText);
        String status = ContentValues.cardStatus(item.status());

        int added = 0;
        int deleted = 0;
        Map<String, Object> resultMap = ContentValues.asMap(result);
        if (resultMap != null) {
            added = number(resultMap.get("added"));
            deleted = number(resultMap.get("deleted"));
        }

        ThreadView.DiffStats diffStats =
                ToolCatalog.of(name).fileTool() && (added > 0 || deleted > 0)
                        ? new ThreadView.DiffStats(added, deleted)
                        : null;

        // 这里只建立执行事实中的基础占位，不开放按钮；HitlService 再合并 Inbox 接收状态。
        // 否则刷新可能把已收下但尚未处理的答复误显示成“仍可审批”。
        ThreadView.HitlBlock hitl = null;
        if ("suspended".equals(status)) {
            hitl =
                    new ThreadView.HitlBlock(
                            item.call() == null ? null : item.call().getId(),
                            "等待用户确认",
                            argumentMap,
                            null,
                            null,
                            null);
        }

        return new ThreadView.ToolCardViewModel(
                "tool-card",
                item.call() == null ? stripPrefix(item.id(), "tool:" + item.turnId() + ":") : item.call().getId(),
                item.turnId(),
                item.runId(),
                name,
                ToolCatalog.displayTitle(name, argumentMap, added, deleted),
                status,
                arguments,
                result,
                "failed".equals(status) ? resultText : null,
                ContentValues.textOf(item.progress()),
                diffStats,
                null,
                hitl,
                iso(item.startedAt()),
                iso(item.endedAt()),
                duration(item.startedAt(), item.endedAt()));
    }

    private static ThreadView.PendingInteraction pending(ChatHistory.Frame frame) {
        Map<String, Object> data = frame.data();
        String requestId = string(data.get("requestId"));
        String toolCallId = null;
        String askPrompt = "等待用户确认";
        Object payload = null;

        Object callValue = data.get("call");
        if (callValue != null) {
            try {
                ToolUseBlock call = Payloads.toolUse(callValue);
                toolCallId = call.getId();
                payload = call.getInput();
                askPrompt = string(data.get("kind")) + "：" + call.getName();
            } catch (RuntimeException ignored) {
                // 无法还原调用参数时保留 requestId 级待答提示
            }
        }
        return new ThreadView.PendingInteraction(
                requestId, toolCallId, frame.turnId(), frame.runId(), askPrompt, payload);
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String iso(long millis) {
        return millis <= 0 ? null : Instant.ofEpochMilli(millis).toString();
    }

    private static Long duration(long started, long ended) {
        return started <= 0 || ended <= 0 || ended < started ? null : ended - started;
    }

    private static String stripPrefix(String value, String prefix) {
        return value != null && value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
