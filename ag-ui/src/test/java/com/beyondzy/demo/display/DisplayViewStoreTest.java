package com.beyondzy.demo.display;

import com.beyondzy.demo.readmodel.ThreadView;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证展示索引跨进程复用、类型恢复和水位隔离，不调用真实模型。 */
@org.junit.jupiter.api.Disabled("旧本地存储测试装配已移除，待改用隔离的 MySQL 测试库")
class DisplayViewStoreTest {
    @TempDir Path workspace;

    private ThreadView.SnapshotResponse view(long seq) {
        var message = new ThreadView.MessageItem("message", "m1", "t1", "r1", "user", "中文历史", false);
        var card = new ThreadView.ToolCardViewModel("tool-card", "c1", "t1", "r1", "lookup_order",
                "查询订单", "completed", Map.of("order_id", "A1001"), Map.of("价格", 100), null,
                null, new ThreadView.DiffStats(1, 0), null, null, null, null, 20L);
        return new ThreadView.SnapshotResponse("test", List.of(message, card), List.of(), seq,
                "test:" + seq, null);
    }

    @Test void 重开后消息与嵌套工具类型保持一致() {
        var store = new DisplayViewStore(workspace);
        store.save("test", 12, true, view(12));
        var saved = new DisplayViewStore(workspace).read("test", 12);
        assertNotNull(saved);
        assertEquals(view(12), saved.view());
        assertTrue(saved.hasInteractions());
        assertNull(store.read("test", 13));
        assertNull(store.read("other", 12));
    }

    @Test void 较旧写入不能覆盖新水位且拒绝错配视图() {
        var store = new DisplayViewStore(workspace);
        store.save("test", 13, false, view(13));
        store.save("test", 12, false, view(12));
        assertNotNull(store.read("test", 13));
        assertNull(store.read("test", 12));
        assertThrows(IllegalArgumentException.class, () -> store.save("test", 14, false, view(13)));
    }

    @Test void 损坏载荷与未知格式只作为未命中() throws Exception {
        var store = new DisplayViewStore(workspace);
        store.save("test", 12, false, view(12));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + workspace.resolve("thread-catalog.sqlite3"));
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE display_view SET payload='无效内容'");
            assertNull(store.read("test", 12));
            statement.executeUpdate("UPDATE display_view SET format=999");
            assertNull(store.read("test", 12));
        }
    }
}
