package com.beyondzy.demo.session;

import com.beyondzy.demo.snapshot.ChatHistory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** 工作区 SQLite 会话目录和已完成会话的展示快照；执行日志仍是会话内容的权威来源。 */
public final class ThreadCatalog {
    public record Summary(String id, String title, long createdAt, long updatedAt, long seq, boolean named) {}
    public record Page(List<Summary> threads, String nextCursor) {}
    public record DisplayMessage(String id, String role, String text) {}
    public record DisplayTurn(String runId, String status) {}
    public record DisplaySnapshot(long seq, List<DisplayMessage> messages, List<DisplayTurn> turns) {}
    private record Position(long updatedAt, String id, long expires) {}

    private final Path database;

    ThreadCatalog(Path workspace) {
        this.database = workspace.resolve("thread-catalog.sqlite3");
        migrate();
    }

    private void migrate() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS thread_catalog(
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        seq INTEGER NOT NULL,
                        named INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS display_head(
                        thread_id TEXT PRIMARY KEY,
                        seq INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS display_message(
                        thread_id TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        message_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        PRIMARY KEY(thread_id, ordinal)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS display_turn(
                        thread_id TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        run_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        PRIMARY KEY(thread_id, ordinal)
                    )
                    """);
        } catch (SQLException error) {
            throw new IllegalStateException("无法初始化会话目录 SQLite", error);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    synchronized Summary get(String id) {
        try (Connection connection = open();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT id,title,created_at,updated_at,seq,named FROM thread_catalog WHERE id=?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? summary(rows) : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("无法读取会话目录", error);
        }
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
        try (Connection connection = open();
                PreparedStatement query = connection.prepareStatement("SELECT id FROM thread_catalog");
                ResultSet rows = query.executeQuery()) {
            List<String> stale = new ArrayList<>();
            while (rows.next()) {
                String id = rows.getString(1);
                if (!loggedIds.contains(id)) stale.add(id);
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM thread_catalog WHERE id=?")) {
                for (String id : stale) {
                    delete.setString(1, id);
                    delete.executeUpdate();
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("无法清理会话目录", error);
        }
    }

    synchronized Page page(int requestedLimit, String cursor) {
        int limit = requestedLimit <= 0 ? 20 : Math.min(requestedLimit, 100);
        Position after = decode(cursor);
        List<Summary> rows = new ArrayList<>();
        String sql = after == null
                ? "SELECT id,title,created_at,updated_at,seq,named FROM thread_catalog ORDER BY updated_at DESC, id DESC LIMIT ?"
                : """
                  SELECT id,title,created_at,updated_at,seq,named FROM thread_catalog
                  WHERE updated_at < ? OR (updated_at = ? AND id < ?)
                  ORDER BY updated_at DESC, id DESC LIMIT ?
                  """;
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement(sql)) {
            if (after == null) {
                query.setInt(1, limit + 1);
            } else {
                query.setLong(1, after.updatedAt());
                query.setLong(2, after.updatedAt());
                query.setString(3, after.id());
                query.setInt(4, limit + 1);
            }
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) rows.add(summary(result));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("无法分页读取会话目录", error);
        }
        boolean more = rows.size() > limit;
        List<Summary> page = more ? rows.subList(0, limit) : rows;
        long expires = after == null ? System.currentTimeMillis() + 30 * 60_000L : after.expires();
        String next = more ? encode(page.getLast(), expires) : null;
        return new Page(List.copyOf(page), next);
    }

    synchronized DisplaySnapshot display(String id, long seq) { return readDisplay(id, seq, false); }
    synchronized DisplaySnapshot previousDisplay(String id, long seq) { return readDisplay(id, seq, true); }

    private DisplaySnapshot readDisplay(String id, long seq, boolean previous) {
        try (Connection connection = open()) {
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
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT message_id,role,content FROM display_message WHERE thread_id=? ORDER BY ordinal")) {
                    query.setString(1, id);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) messages.add(new DisplayMessage(rows.getString(1), rows.getString(2), rows.getString(3)));
                    }
                }
                List<DisplayTurn> turns = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT run_id,status FROM display_turn WHERE thread_id=? ORDER BY ordinal")) {
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
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("display_message", "display_turn")) {
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE thread_id=?")) {
                        delete.setString(1, id); delete.executeUpdate();
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO display_message(thread_id,ordinal,message_id,role,content) VALUES(?,?,?,?,?)")) {
                    int ordinal = 0;
                    for (DisplayMessage message : value.messages()) {
                        insert.setString(1, id); insert.setInt(2, ordinal++); insert.setString(3, message.id());
                        insert.setString(4, message.role()); insert.setString(5, message.text()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO display_turn(thread_id,ordinal,run_id,status) VALUES(?,?,?,?)")) {
                    int ordinal = 0;
                    for (DisplayTurn turn : value.turns()) {
                        insert.setString(1, id); insert.setInt(2, ordinal++); insert.setString(3, turn.runId());
                        insert.setString(4, turn.status()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement head = connection.prepareStatement("""
                        INSERT INTO display_head(thread_id,seq) VALUES(?,?)
                        ON CONFLICT(thread_id) DO UPDATE SET seq=excluded.seq
                        """)) {
                    head.setString(1, id); head.setLong(2, value.seq()); head.executeUpdate();
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback(); throw error;
            }
        } catch (SQLException error) { throw new IllegalStateException("无法保存会话展示快照", error); }
    }

    private void save(Summary value) {
        try (Connection connection = open();
                PreparedStatement write = connection.prepareStatement("""
                        INSERT INTO thread_catalog(id,title,created_at,updated_at,seq,named) VALUES(?,?,?,?,?,?)
                        ON CONFLICT(id) DO UPDATE SET
                          title=excluded.title,
                          created_at=excluded.created_at,
                          updated_at=excluded.updated_at,
                          seq=excluded.seq,
                          named=excluded.named
                        """)) {
            write.setString(1, value.id());
            write.setString(2, value.title());
            write.setLong(3, value.createdAt());
            write.setLong(4, value.updatedAt());
            write.setLong(5, value.seq());
            write.setInt(6, value.named() ? 1 : 0);
            write.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("无法保存会话目录", error);
        }
    }

    private static Summary summary(ResultSet rows) throws SQLException {
        return new Summary(rows.getString("id"), rows.getString("title"), rows.getLong("created_at"),
                rows.getLong("updated_at"), rows.getLong("seq"), rows.getInt("named") != 0);
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
