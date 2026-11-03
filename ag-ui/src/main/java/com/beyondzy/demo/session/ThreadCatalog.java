package com.beyondzy.demo.session;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.List;
import javax.sql.DataSource;

/** 从 SessionLog 和 Inbox 派生会话目录；已完成会话的展示快照仍保存在 MySQL。 */
public final class ThreadCatalog {
    public record Summary(String id, String title, long createdAt, long updatedAt, long seq, boolean named) {}
    public record Page(List<Summary> threads, String nextCursor) {}
    public record DisplayMessage(String id, String role, String text) {}
    public record DisplayTurn(String runId, String status) {}
    public record DisplaySnapshot(long seq, List<DisplayMessage> messages, List<DisplayTurn> turns) {}
    private record Position(long updatedAt, String id, long expires) {}

    private record Cached(Summary summary, long inboxSeq) {}

    private final Supplier<List<String>> ids;
    private final Function<String, SessionLog> logs;
    private final Map<String, Cached> summaries = new HashMap<>();
    private final DataSource dataSource;

    ThreadCatalog(Supplier<List<String>> ids, Function<String, SessionLog> logs, DataSource dataSource) {
        this.ids = ids;
        this.logs = logs;
        this.dataSource = dataSource;
    }

    synchronized Summary get(String id) {
        if (!ids.get().contains(id)) return null;
        return sync(id, logs.apply(id));
    }

    /** 只扫描新增事实；标题取自已提交 Inbox 中的首条用户消息。 */
    synchronized Summary sync(String id, SessionLog log) {
        long head = log.head().seq(), inboxHead = log.inbox().head().seq();
        Cached cached = summaries.get(id);
        if (cached != null && cached.summary().seq() == head && cached.inboxSeq() == inboxHead)
            return cached.summary();
        if (head == 0 && inboxHead == 0) {
            summaries.remove(id);
            return null;
        }
        Summary old = cached == null ? null : cached.summary();
        long createdAt = old == null ? Long.MAX_VALUE : old.createdAt();
        long updatedAt = old == null ? 0 : old.updatedAt();
        String name = old == null ? fallback(id) : old.title();
        boolean named = old != null && old.named();
        for (SessionEvent event : log.inbox().scan(cached == null ? 0 : cached.inboxSeq(), inboxHead)) {
            if (event.occurredAt() > 0) {
                createdAt = Math.min(createdAt, event.occurredAt());
                updatedAt = Math.max(updatedAt, event.occurredAt());
            }
            if (!named && event.type().equals("inbox/accepted")) {
                SessionInbox.Command command = event.payload(SessionInbox.Command.class);
                if (!command.kind().equals("submit")) continue;
                for (Msg message : command.messages()) {
                    if (message.getRole() != MsgRole.USER) continue;
                    String value = message.getTextContent();
                    if (value == null || value.isBlank()) continue;
                    name = title(value); named = true; break;
                }
            }
        }
        if (head > 0) {
            SessionEvent latest = log.readAfter(head - 1, 1).getFirst();
            if (latest.occurredAt() > 0) {
                if (old == null && inboxHead == 0) createdAt = Math.min(createdAt, latest.occurredAt());
                updatedAt = Math.max(updatedAt, latest.occurredAt());
            }
        }
        // 兼容早期只有执行日志、没有 Inbox 的会话；正常新会话无需扫描执行前缀。
        if (!named && head > 0) {
            for (SessionEvent event : log.scan(0, head)) {
                if (event.occurredAt() > 0) createdAt = Math.min(createdAt, event.occurredAt());
                if (!event.type().equals("message/user") || event.data().get("message") == null) continue;
                try {
                    Msg message = JsonUtils.getJsonCodec().convertValue(event.data().get("message"), Msg.class);
                    String value = message.getTextContent();
                    if (value != null && !value.isBlank()) {
                        name = title(value);
                        named = true;
                        break;
                    }
                } catch (RuntimeException ignored) {
                    // 旧格式中无法解码的消息不影响其余会话的列表。
                }
            }
        }
        if (createdAt == Long.MAX_VALUE) createdAt = System.currentTimeMillis();
        Summary value = new Summary(id, name, createdAt, Math.max(createdAt, updatedAt), head, named);
        summaries.put(id, new Cached(value, inboxHead));
        return value;
    }

    synchronized Page page(int requestedLimit, String cursor) {
        int limit = requestedLimit <= 0 ? 20 : Math.min(requestedLimit, 100);
        Position after = decode(cursor);
        List<Summary> rows = ids.get().stream().map(id -> sync(id, logs.apply(id)))
                .filter(java.util.Objects::nonNull)
                .filter(value -> after == null || value.updatedAt() < after.updatedAt()
                        || value.updatedAt() == after.updatedAt() && value.id().compareTo(after.id()) < 0)
                .sorted(Comparator.comparingLong(Summary::updatedAt).reversed()
                        .thenComparing(Summary::id, Comparator.reverseOrder()))
                .limit(limit + 1L).toList();
        boolean more = rows.size() > limit;
        List<Summary> result = more ? rows.subList(0, limit) : rows;
        long expires = after == null ? System.currentTimeMillis() + 30 * 60_000L : after.expires();
        String next = more ? encode(result.getLast(), expires) : null;
        return new Page(List.copyOf(result), next);
    }

    synchronized DisplaySnapshot display(String id, long seq) { return readDisplay(id, seq, false); }
    synchronized DisplaySnapshot previousDisplay(String id, long seq) { return readDisplay(id, seq, true); }

    private DisplaySnapshot readDisplay(String id, long seq, boolean previous) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long savedSeq;
                try (PreparedStatement query = connection.prepareStatement("SELECT seq FROM display_head WHERE thread_id=?")) {
                    query.setString(1, id);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) return null;
                        savedSeq = rows.getLong(1);
                    }
                }
                if (previous ? savedSeq >= seq : savedSeq != seq) return null;
                List<DisplayMessage> messages = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement("SELECT message_id,role,content FROM display_message WHERE thread_id=? ORDER BY ordinal")) {
                    query.setString(1, id);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) messages.add(new DisplayMessage(rows.getString(1), rows.getString(2), rows.getString(3)));
                    }
                }
                List<DisplayTurn> turns = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement("SELECT run_id,status FROM display_turn WHERE thread_id=? ORDER BY ordinal")) {
                    query.setString(1, id);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) turns.add(new DisplayTurn(rows.getString(1), rows.getString(2)));
                    }
                }
                connection.commit();
                return new DisplaySnapshot(savedSeq, List.copyOf(messages), List.copyOf(turns));
            } catch (SQLException error) {
                connection.rollback(); throw error;
            }
        } catch (SQLException error) { throw new IllegalStateException("无法读取会话展示快照", error); }
    }

    /** 消息、轮次和水位在同一事务中更新。 */
    synchronized void saveDisplay(String id, DisplaySnapshot value) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("display_message", "display_turn")) {
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE thread_id=?")) {
                        delete.setString(1, id); delete.executeUpdate();
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO display_message(thread_id,ordinal,message_id,role,content) VALUES(?,?,?,?,?)")) {
                    int ordinal = 0;
                    for (DisplayMessage message : value.messages()) {
                        insert.setString(1, id); insert.setInt(2, ordinal++); insert.setString(3, message.id());
                        insert.setString(4, message.role()); insert.setString(5, message.text()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO display_turn(thread_id,ordinal,run_id,status) VALUES(?,?,?,?)")) {
                    int ordinal = 0;
                    for (DisplayTurn turn : value.turns()) {
                        insert.setString(1, id); insert.setInt(2, ordinal++); insert.setString(3, turn.runId());
                        insert.setString(4, turn.status()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement head = connection.prepareStatement("INSERT INTO display_head(thread_id,seq) VALUES(?,?) ON DUPLICATE KEY UPDATE seq=VALUES(seq)")) {
                    head.setString(1, id); head.setLong(2, value.seq()); head.executeUpdate();
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback(); throw error;
            }
        } catch (SQLException error) { throw new IllegalStateException("无法保存会话展示快照", error); }
    }

    public static String title(String text) {
        String trimmed = text.strip();
        return trimmed.length() > 24 ? trimmed.substring(0, 24) + "…" : trimmed;
    }
    public static String fallback(String id) { return "会话 " + (id.length() > 12 ? id.substring(0, 8) : id); }
    public static String iso(long epochMillis) { return Instant.ofEpochMilli(epochMillis).toString(); }

    private static String encode(Summary value, long expires) {
        String plain = expires + ":" + value.updatedAt() + ":" + value.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = plain.split(":", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            long expires = Long.parseLong(parts[0]), time = Long.parseLong(parts[1]);
            String id = parts[2];
            if (expires < System.currentTimeMillis() || time < 0 || !id.matches("[A-Za-z0-9_-]{1,80}"))
                throw new IllegalArgumentException();
            return new Position(time, id, expires);
        } catch (RuntimeException error) { throw new IllegalArgumentException("非法的会话列表游标", error); }
    }
}
