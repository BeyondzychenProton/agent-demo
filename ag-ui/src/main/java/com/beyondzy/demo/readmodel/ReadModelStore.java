package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.snapshot.ChatItems;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** 单进程展示读库：历史页、内容块和续接变更使用同一事务水位。 */
public final class ReadModelStore implements AutoCloseable {
    public static final long SNAPSHOT_TTL = 30 * 60_000L;
    public static final long CHANGE_TTL = 24 * 60 * 60_000L;
    public record State(String generation, long revision, long execSeq, long inboxSeq,
                        long updatedAt, String leaseTag, String checkpoint, String control) {}
    public record Detail(int bytes, String encoding) {}
    public record ViewItem(String itemId, long orderKey, long contentVersion,
                           Map<String, Object> item, Map<String, Detail> details) {}
    public record Change(String cursor, long revision, ViewItem item) {}
    public record Page(List<ViewItem> items, String snapshotToken, String nextCursor,
                       boolean hasMore, String eventCursor, Map<String, Long> sourceWatermark,
                       long projectionUpdatedAt) {}
    public record Content(List<String> blocks, String nextCursor, String encoding) {}
    private record Cursor(String thread, String generation, long revision, long before, long expires) {}
    private final Connection db;
    public ReadModelStore(DataSource dataSource) {
        try {
            db = dataSource.getConnection();
        } catch (SQLException error) { throw new IllegalStateException("无法连接 MySQL 展示读库", error); }
    }

    @Deprecated(forRemoval = true)
    public ReadModelStore(Path workspace) {
        throw new UnsupportedOperationException("旧文件型读模型存储已移除，请注入 MySQL 数据源");
    }

    public synchronized State state(String thread) {
        try (var query = db.prepareStatement("SELECT * FROM rm_head WHERE thread=?")) {
            query.setString(1, thread);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new State(rows.getString("generation"), rows.getLong("revision"),
                        rows.getLong("exec_seq"), rows.getLong("inbox_seq"), rows.getLong("updated_at"),
                        rows.getString("lease_tag"), rows.getString("checkpoint"), rows.getString("control")) : null;
            }
        } catch (SQLException error) { throw sql(error); }
    }

    /** 仅清理单个会话的过期派生格式，权威 SessionLog 与 Inbox 完全不受影响。 */
    public synchronized void invalidate(String thread) {
        try {
            db.setAutoCommit(false);
            for (String table : List.of("rm_content", "rm_change", "rm_pending", "rm_item", "rm_raw", "rm_head")) {
                try (var delete = db.prepareStatement("DELETE FROM " + table + " WHERE thread=?")) {
                    delete.setString(1, thread); delete.executeUpdate();
                }
            }
            db.commit();
        } catch (SQLException error) {
            try { db.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            throw sql(error);
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException error) { throw sql(error); }
        }
    }

    public synchronized ChatItems.Item raw(String thread, String id) {
        try (var query = db.prepareStatement("SELECT payload FROM rm_raw WHERE thread=? AND id=?")) {
            query.setString(1, thread); query.setString(2, id);
            try (var rows = query.executeQuery()) {
                return rows.next() ? decode(rows.getString(1), ChatItems.Item.class) : null;
            }
        } catch (SQLException error) { throw sql(error); }
    }

    public synchronized Long order(String thread, String id) {
        try (var query = db.prepareStatement("SELECT order_key FROM rm_item WHERE thread=? AND id=? AND until_revision IS NULL")) {
            query.setString(1, thread); query.setString(2, id);
            try (var rows = query.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        } catch (SQLException error) { throw sql(error); }
    }

    public synchronized long maxOrder(String thread) {
        try (var query = db.prepareStatement("SELECT COALESCE(MAX(order_key),0) FROM rm_item WHERE thread=?")) {
            query.setString(1, thread);
            try (var rows = query.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        } catch (SQLException error) { throw sql(error); }
    }

    public synchronized List<ViewItem> pending(String thread) { return pending(thread, 0, 20); }

    /** 未解决审批独立于历史窗口，较早的请求不会因为只加载尾页而消失。 */
    public synchronized List<ViewItem> pending(String thread, long after, int limit) {
        try (var query = db.prepareStatement("""
                SELECT i.payload FROM rm_pending p JOIN rm_item i ON p.thread=i.thread AND p.id=i.id
                WHERE p.thread=? AND i.until_revision IS NULL AND p.order_key>? ORDER BY p.order_key LIMIT ?
                """)) {
            query.setString(1, thread); query.setLong(2, after); query.setInt(3, Math.max(1, Math.min(limit, 50)));
            var items = new ArrayList<ViewItem>();
            try (var rows = query.executeQuery()) { while (rows.next()) items.add(decode(rows.getString(1), ViewItem.class)); }
            return items;
        } catch (SQLException error) { throw sql(error); }
    }

    public synchronized int pendingCount(String thread) {
        try (var query = db.prepareStatement("""
                SELECT COUNT(*) FROM rm_pending WHERE thread=?
                """)) {
            query.setString(1, thread);
            try (var rows = query.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        } catch (SQLException error) { throw sql(error); }
    }

    /** 原始聚合项、页面版本、变更记录和双水位同时提交，不发布半批状态。 */
    public synchronized void commit(String thread, State previous, long execSeq, long inboxSeq,
                                    String leaseTag, String checkpoint, String control,
                                    List<ChatItems.Item> rawItems, Map<String, Object> views,
                                    Map<String, Long> orders) {
        long revision = previous == null ? 0 : previous.revision();
        String generation = previous == null ? UUID.randomUUID().toString() : previous.generation();
        long now = System.currentTimeMillis();
        try {
            db.setAutoCommit(false);
            for (var item : rawItems) {
                try (var insert = db.prepareStatement("INSERT INTO rm_raw VALUES(?,?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload)")) {
                    insert.setString(1, thread); insert.setString(2, item.id()); insert.setString(3, json(item)); insert.executeUpdate();
                }
            }
            for (var entry : views.entrySet()) {
                String id = entry.getKey();
                var preview = preview(thread, id, orders.get(id), revision + 1, entry.getValue(), false);
                String fingerprint = json(new ViewItem(id, preview.orderKey(), 0, preview.item(), preview.details()));
                String old = currentFingerprint(thread, id);
                if (fingerprint.equals(old)) {
                    // 内容引用大小相同时仍可能改变；完整聚合项由调用方保证只把真正变动项送入。
                    // 对存在分块的项不能仅用预览判断，必须产生新版本。
                    if (preview.details().isEmpty()) continue;
                }
                revision++;
                var item = preview(thread, id, orders.get(id), revision, entry.getValue(), true);
                try (var close = db.prepareStatement("UPDATE rm_item SET until_revision=? WHERE thread=? AND id=? AND until_revision IS NULL")) {
                    close.setLong(1, revision); close.setString(2, thread); close.setString(3, id); close.executeUpdate();
                }
                try (var insert = db.prepareStatement("INSERT INTO rm_item VALUES(?,?,?,NULL,?,?,?)")) {
                    insert.setString(1, thread); insert.setString(2, id); insert.setLong(3, revision);
                    insert.setLong(4, item.orderKey()); insert.setString(5, json(item)); insert.setLong(6, now); insert.executeUpdate();
                }
                try (var insert = db.prepareStatement("INSERT INTO rm_change VALUES(?,?,?,?)")) {
                    insert.setString(1, thread); insert.setLong(2, revision); insert.setString(3, id); insert.setLong(4, now); insert.executeUpdate();
                }
                try (var delete = db.prepareStatement("DELETE FROM rm_pending WHERE thread=? AND id=?")) {
                    delete.setString(1, thread); delete.setString(2, id); delete.executeUpdate();
                }
                if (item.item().get("hitl") instanceof Map<?, ?> h
                        && Set.of("pending", "processing", "reconciling").contains(h.get("interactionStatus"))) {
                    try (var insert = db.prepareStatement("INSERT INTO rm_pending VALUES(?,?,?)")) {
                        insert.setString(1, thread); insert.setString(2, id); insert.setLong(3, item.orderKey()); insert.executeUpdate();
                    }
                }
            }
            try (var head = db.prepareStatement("""
                    INSERT INTO rm_head VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE
                    revision=VALUES(revision),exec_seq=VALUES(exec_seq),inbox_seq=VALUES(inbox_seq),
                    updated_at=VALUES(updated_at),lease_tag=VALUES(lease_tag),checkpoint=VALUES(checkpoint),control=VALUES(control)
                    """)) {
                head.setString(1, thread); head.setString(2, generation); head.setLong(3, revision);
                head.setLong(4, execSeq); head.setLong(5, inboxSeq); head.setLong(6, now);
                head.setString(7, leaseTag); head.setString(8, checkpoint); head.setString(9, control); head.executeUpdate();
            }
            db.commit();
        } catch (SQLException | RuntimeException error) {
            try { db.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            throw new IllegalStateException("提交展示投影失败", error);
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException error) { throw sql(error); }
        }
    }

    private String currentFingerprint(String thread, String id) throws SQLException {
        try (var query = db.prepareStatement("SELECT payload FROM rm_item WHERE thread=? AND id=? AND until_revision IS NULL")) {
            query.setString(1, thread); query.setString(2, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                var old = decode(rows.getString(1), ViewItem.class);
                return json(new ViewItem(old.itemId(), old.orderKey(), 0, old.item(), old.details()));
            }
        }
    }

    /** 截断发生在数据库写入时；读页不会先加载整条大消息再截取。 */
    @SuppressWarnings("unchecked")
    private ViewItem preview(String thread, String id, long order, long version, Object value, boolean write) throws SQLException {
        Map<String, Object> fields = new LinkedHashMap<>(decode(json(value), Map.class));
        for (String label : List.of("displayTitle", "name")) {
            if (fields.get(label) instanceof String text && text.codePointCount(0, text.length()) > 256)
                fields.put(label, prefix(text, 256) + "…");
        }
        Map<String, Detail> details = new LinkedHashMap<>();
        for (String field : List.of("text", "arguments", "result", "error", "progress", "hitl")) {
            Object content = fields.get(field);
            if (content == null) continue;
            String encoding = content instanceof String ? "text" : "json";
            String text = content instanceof String str ? str : json(content);
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes <= 4096) continue;
            details.put(field, new Detail(bytes, encoding));
            // 审批权限和请求编号保留，仅移走可能很大的参数字段。
            if (field.equals("hitl") && content instanceof Map<?, ?> map) {
                var small = new LinkedHashMap<String, Object>((Map<String, Object>) map);
                small.put("payload", "参数较长，请展开查看"); fields.put(field, small);
            } else fields.put(field, prefix(text, 1024) + "…");
            if (!write) continue;
            List<String> blocks = blocks(text);
            for (int index = 0; index < blocks.size(); index++) {
                String block = blocks.get(index), hash = hash(block);
                try (var insert = db.prepareStatement("INSERT IGNORE INTO rm_block VALUES(?,?)")) {
                    insert.setString(1, hash); insert.setString(2, block); insert.executeUpdate();
                }
                try (var insert = db.prepareStatement("INSERT INTO rm_content VALUES(?,?,?,?,?,?,?)")) {
                    insert.setString(1, thread); insert.setString(2, id); insert.setLong(3, version);
                    insert.setString(4, field); insert.setInt(5, index); insert.setString(6, hash); insert.setString(7, encoding); insert.executeUpdate();
                }
            }
        }
        return new ViewItem(id, order, version, fields, details);
    }

    public synchronized Page page(String thread, int requested, String before) {
        State state = state(thread);
        if (state == null) throw new IllegalStateException("展示投影尚未建立");
        int limit = Math.max(1, Math.min(requested, 50));
        Cursor cursor = before == null || before.isBlank() ? new Cursor(thread, state.generation(), state.revision(),
                Long.MAX_VALUE, System.currentTimeMillis() + SNAPSHOT_TTL) : parse(before, thread, state);
        if (cursor.before() <= 0) throw new IllegalArgumentException("事件游标不能作为历史页游标");
        List<ViewItem> items = new ArrayList<>();
        int bytes = 2048;
        boolean more = false;
        String pageSql = cursor.revision() == state.revision() ? """
                SELECT payload FROM rm_item WHERE thread=? AND revision<=? AND until_revision IS NULL
                AND ? >= 0 AND order_key<? ORDER BY order_key DESC LIMIT ?
                """ : """
                SELECT payload FROM rm_item WHERE thread=? AND revision<=?
                AND (until_revision IS NULL OR until_revision>?) AND order_key<? ORDER BY order_key DESC LIMIT ?
                """;
        try (var query = db.prepareStatement(pageSql)) {
            query.setString(1, thread); query.setLong(2, cursor.revision()); query.setLong(3, cursor.revision());
            query.setLong(4, cursor.before()); query.setInt(5, limit + 1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String payload = rows.getString(1);
                    int nextSize = payload.getBytes(StandardCharsets.UTF_8).length;
                    // 给状态回执及待答摘要预留预算，完整HTTP响应继续保持有界。
                    if (items.size() == limit || bytes + nextSize > 192 * 1024) { more = true; break; }
                    items.add(decode(payload, ViewItem.class)); bytes += nextSize;
                }
            }
        } catch (SQLException error) { throw sql(error); }
        String next = more ? token(new Cursor(thread, cursor.generation(), cursor.revision(),
                items.get(items.size() - 1).orderKey(), cursor.expires())) : null;
        Collections.reverse(items);
        return new Page(List.copyOf(items), token(cursor), next, more,
                eventCursor(thread, state.generation(), cursor.revision()),
                Map.of("execSeq", state.execSeq(), "inboxSeq", state.inboxSeq()), state.updatedAt());
    }

    public synchronized List<Change> changes(String thread, String after) {
        State state = state(thread);
        if (state == null) return List.of();
        Cursor cursor = parse(after, thread, state);
        if (cursor.before() != 0) throw new IllegalArgumentException("历史页游标不能作为事件游标");
        try (var query = db.prepareStatement("SELECT MIN(revision) FROM rm_change WHERE thread=?")) {
            query.setString(1, thread);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    long minimum = rows.getLong(1);
                    if (cursor.revision() < state.revision() && (minimum == 0 || cursor.revision() < minimum - 1))
                        throw new Gone("补读记录已过期，请重新加载");
                }
            }
        } catch (SQLException error) { throw sql(error); }
        List<Change> result = new ArrayList<>();
        try (var query = db.prepareStatement("""
                SELECT c.revision,i.payload FROM rm_change c JOIN rm_item i
                ON c.thread=i.thread AND c.item_id=i.id AND c.revision=i.revision
                WHERE c.thread=? AND c.revision>? ORDER BY c.revision LIMIT 50
                """)) {
            query.setString(1, thread); query.setLong(2, cursor.revision());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long revision = rows.getLong(1);
                    result.add(new Change(eventCursor(thread, state.generation(), revision), revision,
                            decode(rows.getString(2), ViewItem.class)));
                }
            }
        } catch (SQLException error) { throw sql(error); }
        return result;
    }

    public synchronized Content content(String thread, String id, long version, String field, int after) {
        if (version < 1 || after < 0) throw new IllegalArgumentException("非法内容位置");
        var blocks = new ArrayList<String>(); String encoding = "text"; boolean more = false; int bytes = 256;
        try (var query = db.prepareStatement("""
                SELECT b.body,c.encoding FROM rm_content c JOIN rm_block b ON c.hash=b.hash
                WHERE c.thread=? AND c.id=? AND c.revision=? AND c.field=? AND c.block_no>=?
                ORDER BY c.block_no LIMIT 17
                """)) {
            query.setString(1, thread); query.setString(2, id); query.setLong(3, version);
            query.setString(4, field); query.setInt(5, after);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String body = rows.getString(1);
                    int size = json(body).getBytes(StandardCharsets.UTF_8).length;
                    if (blocks.size() == 16 || bytes + size > 120 * 1024) { more = true; break; }
                    blocks.add(body); bytes += size; encoding = rows.getString(2);
                }
            }
        } catch (SQLException error) { throw sql(error); }
        if (blocks.isEmpty()) throw new Gone("内容版本不存在或已过期");
        return new Content(List.copyOf(blocks), more ? Integer.toString(after + blocks.size()) : null, encoding);
    }

    /** 清理仅影响过期派生版本；当前版本以及补读窗口内的版本一直保留。 */
    public synchronized void cleanup() {
        try (var delete = db.prepareStatement("DELETE FROM rm_change WHERE created_at<?")) {
            delete.setLong(1, System.currentTimeMillis() - CHANGE_TTL); delete.executeUpdate();
            try (var statement = db.createStatement()) {
                String stale = "SELECT old.thread,old.id,old.revision FROM rm_item old WHERE old.until_revision IS NOT NULL AND old.created_at<"
                        + (System.currentTimeMillis() - CHANGE_TTL)
                        + " AND NOT EXISTS(SELECT 1 FROM rm_change c WHERE c.thread=old.thread AND c.revision=old.revision)"
                        + " AND NOT EXISTS(SELECT 1 FROM rm_item newer WHERE newer.thread=old.thread AND newer.id=old.id AND newer.revision>=old.until_revision AND newer.created_at>="
                        + (System.currentTimeMillis() - SNAPSHOT_TTL) + ") LIMIT 1000";
                record OldItem(String thread, String id, long revision) {}
                var obsolete = new ArrayList<OldItem>();
                try (var rows = statement.executeQuery(stale)) {
                    while (rows.next()) obsolete.add(new OldItem(rows.getString(1), rows.getString(2), rows.getLong(3)));
                }
                try (var remove = db.prepareStatement("DELETE FROM rm_item WHERE thread=? AND id=? AND revision=?")) {
                    for (var item : obsolete) {
                        remove.setString(1, item.thread()); remove.setString(2, item.id());
                        remove.setLong(3, item.revision()); remove.addBatch();
                    }
                    if (!obsolete.isEmpty()) remove.executeBatch();
                }
                statement.executeUpdate("DELETE FROM rm_content WHERE NOT EXISTS(SELECT 1 FROM rm_item i WHERE i.thread=rm_content.thread AND i.id=rm_content.id AND i.revision=rm_content.revision)");
                statement.executeUpdate("DELETE FROM rm_block WHERE NOT EXISTS(SELECT 1 FROM rm_content c WHERE c.hash=rm_block.hash)");
            }
        } catch (SQLException error) { throw sql(error); }
    }

    public static String eventCursor(String thread, String generation, long revision) {
        return token(new Cursor(thread, generation, revision, 0, Long.MAX_VALUE));
    }

    private static Cursor parse(String token, String thread, State state) {
        try {
            Cursor cursor = decode(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8), Cursor.class);
            if (!thread.equals(cursor.thread()) || cursor.revision() < 0 || cursor.revision() > state.revision())
                throw new IllegalArgumentException("游标不属于当前会话或超出水位");
            if (!state.generation().equals(cursor.generation()) || cursor.expires() < System.currentTimeMillis())
                throw new Gone("历史版本已过期，请重新加载");
            return cursor;
        } catch (Gone error) { throw error; }
        catch (RuntimeException error) { throw new IllegalArgumentException("非法展示游标", error); }
    }

    public static class Gone extends RuntimeException { public Gone(String message) { super(message); } }
    private static String token(Cursor value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json(value).getBytes(StandardCharsets.UTF_8));
    }
    public static String json(Object value) { return JsonUtils.getJsonCodec().toJson(value); }
    public static <T> T decode(String text, Class<T> type) { return JsonUtils.getJsonCodec().fromJson(text, type); }
    private static IllegalStateException sql(SQLException error) { return new IllegalStateException("展示读库访问失败", error); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    public static List<String> blocks(String text) {
        var result = new ArrayList<String>(); var current = new StringBuilder(); int bytes = 0;
        for (int index = 0; index < text.length();) {
            int cp = text.codePointAt(index), size = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes + size > 8192) { result.add(current.toString()); current.setLength(0); bytes = 0; }
            current.appendCodePoint(cp); bytes += size; index += Character.charCount(cp);
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }
    private static String prefix(String text, int codePoints) {
        return text.substring(0, text.offsetByCodePoints(0, Math.min(codePoints, text.codePointCount(0, text.length()))));
    }
    @Override public synchronized void close() { try { db.close(); } catch (SQLException error) { throw sql(error); } }
}
