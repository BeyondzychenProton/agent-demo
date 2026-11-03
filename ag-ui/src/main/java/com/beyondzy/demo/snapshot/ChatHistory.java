package com.beyondzy.demo.snapshot;

import io.agentscope.core.message.Msg;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionViews;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照与游标：快照和它的 cursor 都来自同一个已提交前缀，保证不漏、不重（大纲 5.4.1）。
 *
 * <p>诊断类字段（执行错误、不确定工具、工作消息数、原始 trace 尾窗）已按最小闭环砍掉。
 */
public final class ChatHistory {

    /** 一轮 turn 的归并视图：turnId、本次执行 runId、终态。 */
    public record Turn(String turnId, String runId, String status) {}

    /** 一条原始已提交事件的轻量帧（只取展示需要的字段，大载荷按需读取）。 */
    public record Frame(
            long seq,
            String eventId,
            String type,
            String turnId,
            String runId,
            Map<String, Object> data) {}

    /**
     * 快照响应：
     * messages=完整消息历史，items=聚合后的消息项/工具卡片，turns=按 turnId 归并的轮次，
     * pending=执行日志尚未解决的交互（完整答复进度还需联合 Inbox），checkpointSeq=最近检查点序号。
     */
    public record Snapshot(
            String sessionId,
            String cursor,
            List<Msg> messages,
            List<ChatItems.Item> items,
            List<Turn> turns,
            List<Frame> pending,
            long checkpointSeq,
            String activeRun,
            boolean hasInteractions) {}

    /** 现算快照：scan 已提交前缀 → transcript 消息 → ChatItems 聚合卡片 → pending 待答。 */
    public static Snapshot snapshot(String sessionId, SessionLog log) {
        var head = log.head();
        // 活跃 run 必须与下方事实扫描读自同一个 head，避免跨越 run 写入租约造成误标
        String activeRun = head.leaseUntil() > System.currentTimeMillis() ? head.owner() : null;
        var facts = new ArrayList<SessionEvent>();
        log.scan(0, head.seq()).forEach(facts::add);
        var prefix = new Prefix(head, List.copyOf(facts));

        // 按 turnId 归并轮次：run/start 标 running/interrupted，run/end 用终态覆盖
        var turns = new LinkedHashMap<String, Turn>();
        long checkpoint = 0;
        boolean hasInteractions = false;
        for (var fact : facts) {
            if (fact.type().equals("state/checkpoint")) checkpoint = fact.seq();
            // 页面读取只在存在审批事实时才需要联合查询 Inbox；普通会话可跳过整段交互重扫。
            if (fact.type().startsWith("interaction/")) hasInteractions = true;
            if (fact.turnId() == null) continue;
            if (fact.type().equals("run/start"))
                turns.put(
                        fact.turnId(),
                        new Turn(
                                fact.turnId(),
                                fact.executionRunId(),
                                fact.executionRunId().equals(activeRun)
                                        ? "running"
                                        : "interrupted"));
            if (fact.type().equals("run/end"))
                turns.put(
                        fact.turnId(),
                        new Turn(
                                fact.turnId(),
                                fact.executionRunId(),
                                String.valueOf(fact.data().get("status"))));
        }

        return new Snapshot(
                sessionId,
                cursor(sessionId, head.seq()),
                SessionViews.transcript(prefix).messages(),
                ChatItems.read(facts),
                List.copyOf(turns.values()),
                SessionInteractions.pending(prefix).values().stream()
                        .map(ChatHistory::frame)
                        .toList(),
                checkpoint,
                activeRun,
                hasInteractions);
    }

    /** 利用框架的消息视图，只整理指定范围内新增的已提交事实。 */
    public static List<Msg> messagesFrom(SessionLog.Head head, List<SessionEvent> facts) {
        return SessionViews.transcript(new Prefix(head, facts)).messages();
    }

    /** 拼装游标字符串 session:seq。 */
    public static String cursor(String session, long seq) {
        return session + ":" + seq;
    }

    /** 解析游标：校验归属本会话并返回 seq；空游标返回 0。 */
    public static long position(String session, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        if (!cursor.startsWith(session + ":"))
            throw new IllegalArgumentException("游标属于其他会话");
        long seq = Long.parseLong(cursor.substring(session.length() + 1));
        if (seq < 0) throw new IllegalArgumentException("非法游标");
        return seq;
    }

    /** 把原始事件压成轻量帧（只取展示需要的字段）。 */
    public static Frame frame(SessionEvent fact) {
        var data = new LinkedHashMap<String, Object>();
        var payload = fact.data();
        for (String key :
                List.of(
                        "message", "requestId", "kind", "call", "status",
                        "reason", "modelCallId", "toolCallId"))
            if (payload.get(key) != null) data.put(key, payload.get(key));
        return new Frame(
                fact.seq(), fact.eventId(), fact.type(),
                fact.turnId(), fact.executionRunId(), data);
    }

    /** 只读视图：SessionViews 不得越过本快照的 head 推进。 */
    private record Prefix(SessionLog.Head head, List<SessionEvent> facts) implements SessionLog {
        public List<SessionEvent> readAfter(long seq, int limit) {
            return facts.stream().filter(e -> e.seq() > seq).limit(limit).toList();
        }

        public Iterable<SessionEvent> scan(long after, long through) {
            return facts.stream().filter(e -> e.seq() > after && e.seq() <= through).toList();
        }

        public Writer acquire(String owner, Duration lease) {
            throw new UnsupportedOperationException();
        }

        public void renew(Writer writer, Duration lease) {
            throw new UnsupportedOperationException();
        }

        public void release(Writer writer) {
            throw new UnsupportedOperationException();
        }

        public Head commit(Writer writer, String batch, long seq, List<SessionEvent> events) {
            throw new UnsupportedOperationException();
        }
    }
}
