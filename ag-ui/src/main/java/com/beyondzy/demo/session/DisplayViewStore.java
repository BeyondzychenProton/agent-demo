package com.beyondzy.demo.session;

import com.beyondzy.demo.contract.ThreadView;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 完整基础视图的派生读索引；只保存展示数据，不保存审批授权与执行状态。 */
final class DisplayViewStore {
    private static final int FORMAT = 1;
    record Saved(long seq, boolean hasInteractions, ThreadView.SnapshotResponse view) {}
    private final Path database;

    DisplayViewStore(Path workspace) {
        this.database = workspace.resolve("thread-catalog.sqlite3");
        migrate();
    }

    private void migrate() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS display_view(
                        thread_id TEXT PRIMARY KEY,
                        seq INTEGER NOT NULL,
                        format INTEGER NOT NULL,
                        has_interactions INTEGER NOT NULL,
                        payload TEXT NOT NULL
                    )
                    """);
        } catch (SQLException error) {
            throw new IllegalStateException("无法初始化展示视图 SQLite", error);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    /** 水位和格式均匹配才返回；旧水位不能冒充当前页面。 */
    synchronized Saved read(String id, long seq) {
        try (var connection = open(); var query = connection.prepareStatement(
                "SELECT format,has_interactions,payload FROM display_view WHERE thread_id=? AND seq=?")) {
            query.setString(1, id);
            query.setLong(2, seq);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != FORMAT) return null;
                try {
                    var view = decode(rows.getString(3));
                    if (!id.equals(view.threadId()) || view.asOfSeq() != seq
                            || !((id + ":" + seq).equals(view.cursor())) || view.activeRun() != null)
                        return null;
                    return new Saved(seq, rows.getInt(2) != 0, view);
                } catch (RuntimeException invalid) {
                    // 派生数据解码失败视为未命中，由现有读取链重新生成，不影响权威日志。
                    return null;
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("无法读取完整展示索引", error);
        }
    }

    /** 仅接收基础视图，禁止持久化带实时审批操作权限的装饰结果。 */
    synchronized void save(String id, long seq, boolean hasInteractions, ThreadView.SnapshotResponse view) {
        if (!id.equals(view.threadId()) || view.asOfSeq() != seq || view.activeRun() != null)
            throw new IllegalArgumentException("展示视图水位不匹配或仍在执行");
        for (var item : view.view()) {
            if (item instanceof ThreadView.ToolCardViewModel card
                    && card.hitl() != null && card.hitl().canRespond())
                throw new IllegalArgumentException("不能持久化审批操作权限");
        }
        try (var connection = open(); var write = connection.prepareStatement("""
                INSERT INTO display_view(thread_id,seq,format,has_interactions,payload) VALUES(?,?,?,?,?)
                ON CONFLICT(thread_id) DO UPDATE SET
                  payload=CASE WHEN excluded.seq>=display_view.seq THEN excluded.payload ELSE display_view.payload END,
                  has_interactions=CASE WHEN excluded.seq>=display_view.seq THEN excluded.has_interactions ELSE display_view.has_interactions END,
                  format=CASE WHEN excluded.seq>=display_view.seq THEN excluded.format ELSE display_view.format END,
                  seq=MAX(display_view.seq, excluded.seq)
                """)) {
            write.setString(1, id);
            write.setLong(2, seq);
            write.setInt(3, FORMAT);
            write.setInt(4, hasInteractions ? 1 : 0);
            write.setString(5, JsonUtils.getJsonCodec().toJson(view));
            write.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("无法保存完整展示索引", error);
        }
    }

    /** 按公开 kind 白名单恢复具体类型，避免给序列化器开放任意 Java 类型。 */
    @SuppressWarnings("unchecked")
    private static ThreadView.SnapshotResponse decode(String json) {
        Map<String, Object> root = JsonUtils.getJsonCodec().fromJson(json, Map.class);
        List<ThreadView.ChatItem> items = new ArrayList<>();
        for (var value : (List<Map<String, Object>>) root.get("view")) {
            items.add(switch (String.valueOf(value.get("kind"))) {
                case "message" -> convert(value, ThreadView.MessageItem.class);
                case "tool-card" -> convert(value, ThreadView.ToolCardViewModel.class);
                default -> throw new IllegalArgumentException("未知展示项类型");
            });
        }
        List<ThreadView.PendingInteraction> pending = new ArrayList<>();
        for (var value : (List<?>) root.get("pendingInteractions"))
            pending.add(convert(value, ThreadView.PendingInteraction.class));
        return new ThreadView.SnapshotResponse((String) root.get("threadId"), List.copyOf(items),
                List.copyOf(pending), ((Number) root.get("asOfSeq")).longValue(),
                (String) root.get("cursor"), root.get("activeRun") == null ? null
                        : convert(root.get("activeRun"), ThreadView.ActiveRun.class));
    }

    private static <T> T convert(Object value, Class<T> type) {
        return JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(value), type);
    }
}
