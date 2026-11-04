package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.readmodel.ThreadView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 历史翻页、实时覆盖与内容分块的契约测试，不依赖模型服务。 */
@org.junit.jupiter.api.Disabled("旧本地存储测试装配已移除，待改用隔离的 MySQL 测试库")
class ReadModelStoreTest {
    @TempDir Path workspace;
    private void put(ReadModelStore store, String id, long order, String text) {
        var state = store.state("thread");
        var message = new ThreadView.MessageItem("message", id, "turn", "run", "assistant", text, false);
        store.commit("thread", state, state == null ? 1 : state.execSeq() + 1, 0, "idle", "{}", "{}",
                List.of(), Map.of(id, message), Map.of(id, order));
    }

    @Test void 翻页固定快照并与新消息隔离() {
        try (var store = new ReadModelStore(workspace)) {
            for (int i = 1; i <= 7; i++) put(store, "m" + i, i, "消息" + i);
            var first = store.page("thread", 3, null);
            assertEquals(List.of("m5", "m6", "m7"), first.items().stream().map(ReadModelStore.ViewItem::itemId).toList());
            put(store, "m4", 4, "修改后"); put(store, "m8", 8, "新增");
            var second = store.page("thread", 3, first.nextCursor());
            assertEquals(List.of("m2", "m3", "m4"), second.items().stream().map(ReadModelStore.ViewItem::itemId).toList());
            assertEquals("消息4", second.items().get(2).item().get("text"));
            var changes = store.changes("thread", first.eventCursor());
            assertEquals(List.of("m4", "m8"), changes.stream().map(change -> change.item().itemId()).toList());
            assertEquals("修改后", changes.get(0).item().item().get("text"));
            var last = store.page("thread", 3, second.nextCursor());
            assertFalse(last.hasMore()); assertEquals("m1", last.items().get(0).itemId());
        }
    }

    @Test void 超长中文与表情按块读取完整且首屏有界() {
        String original = "中文🙂\n```java\n\"代码\"\\\n".repeat(400000);
        assertTrue(original.getBytes(StandardCharsets.UTF_8).length > 10 * 1024 * 1024);
        try (var store = new ReadModelStore(workspace)) {
            put(store, "long", 1, original);
            var page = store.page("thread", 50, null);
            assertTrue(ReadModelStore.json(page).getBytes(StandardCharsets.UTF_8).length < 256 * 1024);
            var item = page.items().get(0);
            assertEquals(original.getBytes(StandardCharsets.UTF_8).length, item.details().get("text").bytes());
            StringBuilder recovered = new StringBuilder(); int position = 0;
            do {
                var content = store.content("thread", "long", item.contentVersion(), "text", position);
                assertTrue(ReadModelStore.json(content).getBytes(StandardCharsets.UTF_8).length <= 128 * 1024);
                recovered.append(String.join("", content.blocks()));
                position = content.nextCursor() == null ? -1 : Integer.parseInt(content.nextCursor());
            } while (position >= 0);
            assertEquals(original, recovered.toString());
        }
    }

    @Test void 重开保持历史版本且跨会话游标被拒绝() {
        String cursor;
        try (var store = new ReadModelStore(workspace)) { put(store, "one", 1, "第一条"); cursor = store.page("thread", 1, null).eventCursor(); }
        try (var store = new ReadModelStore(workspace)) {
            assertEquals("one", store.page("thread", 1, null).items().get(0).itemId());
            assertTrue(store.changes("thread", cursor).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.page("thread", 1, cursor));
            assertThrows(IllegalArgumentException.class, () -> store.changes("thread", store.page("thread", 1, null).snapshotToken()));
            assertThrows(IllegalArgumentException.class, () -> store.changes("thread", "非法游标"));
            assertThrows(ReadModelStore.Gone.class, () -> store.content("thread", "missing", 1, "text", 0));
        }
    }
}
