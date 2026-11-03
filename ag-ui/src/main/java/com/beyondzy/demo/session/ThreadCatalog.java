package com.beyondzy.demo.session;

import com.beyondzy.demo.domain.ThreadCatalogBean;
import com.beyondzy.demo.service.ThreadCatalogService;
import com.beyondzy.demo.snapshot.ChatHistory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/** MySQL 会话目录和已完成会话的展示快照；执行日志仍是会话内容的权威来源。 */
public final class ThreadCatalog {
    public record Summary(String id, String title, long createdAt, long updatedAt, long seq, boolean named) {}
    public record Page(List<Summary> threads, String nextCursor) {}
    public record DisplayMessage(String id, String role, String text) {}
    public record DisplayTurn(String runId, String status) {}
    public record DisplaySnapshot(long seq, List<DisplayMessage> messages, List<DisplayTurn> turns) {}
    private record Position(long updatedAt, String id, long expires) {}

    private final ThreadCatalogService service;
    private final DataSource dataSource;

    ThreadCatalog(ThreadCatalogService service, DataSource dataSource) {
        this.service = service;
        this.dataSource = dataSource;
    }

    @Deprecated(forRemoval = true)
    ThreadCatalog(Path workspace) {
        throw new UnsupportedOperationException("旧文件型会话目录已移除，请注入 MySQL 服务");
    }

    synchronized Summary get(String id) {
        ThreadCatalogBean row = service.findById(id);
        return row == null ? null : summary(row);
    }

    /** 从已提交日志补齐目录摘要。 */
    synchronized void sync(String id, SessionLog log) {
        Summary old = get(id);
        long head = log.head().seq();
        if (old != null && old.seq() == head) return;
        long after = old == null || old.seq() > head ? 0 : old.seq();
        long firstAt = 0, lastAt = 0;
        for (SessionEvent event : log.scan(after, head)) {
            long at = event.occurredAt();
            if (at <= 0) continue;
            if (firstAt == 0) firstAt = at;
            lastAt = at;
        }
        long now = System.currentTimeMillis();
        long createdAt = old == null ? (firstAt > 0 ? firstAt : now) : old.createdAt();
        long updatedAt = old == null ? (lastAt > 0 ? lastAt : createdAt) : Math.max(old.updatedAt(), lastAt);
        String title = old == null ? fallback(id) : old.title();
        boolean named = old != null && old.named();
        if (!named && head > 0) {
            try {
                for (Msg message : ChatHistory.snapshot(id, log).messages()) {
                    if (message.getRole() != MsgRole.USER) continue;
                    String text = message.getTextContent();
                    if (text == null || text.isBlank()) continue;
                    title = title(text); named = true; break;
                }
            } catch (RuntimeException ignored) {
                // 不完整事实留待下一次同步。
            }
        }
        save(new Summary(id, title, createdAt, updatedAt, head, named));
    }

    synchronized void submitted(String id, String text) {
        Summary old = get(id);
        long now = System.currentTimeMillis();
        save(new Summary(id, old != null && old.named() ? old.title() : title(text),
                old == null ? now : old.createdAt(), now, old == null ? 0 : old.seq(), true));
    }

    synchronized void retain(Set<String> loggedIds) {
        for (ThreadCatalogBean row : service.listAll()) {
            if (!loggedIds.contains(row.getId())) service.deleteById(row.getId());
        }
    }

    synchronized Page page(int requestedLimit, String cursor) {
        int limit = requestedLimit <= 0 ? 20 : Math.min(requestedLimit, 100);
        Position after = decode(cursor);
        List<Summary> rows = service.listRecentAfter(limit + 1,
                after == null ? null : after.updatedAt(), after == null ? null : after.id())
                .stream().map(ThreadCatalog::summary).toList();
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

    private void save(Summary value) {
        ThreadCatalogBean row = new ThreadCatalogBean();
        row.setId(value.id()); row.setTitle(value.title()); row.setCreatedAt(value.createdAt());
        row.setUpdatedAt(value.updatedAt()); row.setSeq(value.seq()); row.setNamed(value.named());
        if (service.findById(value.id()) == null) service.create(row);
        else service.update(row);
    }

    private static Summary summary(ThreadCatalogBean row) {
        return new Summary(row.getId(), row.getTitle(), row.getCreatedAt(), row.getUpdatedAt(),
                row.getSeq(), Boolean.TRUE.equals(row.getNamed()));
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
