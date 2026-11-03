package com.beyondzy.demo.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Disabled("旧本地存储测试装配已移除，待改用隔离的 MySQL 测试库")
final class ThreadCatalogTest {
    @TempDir Path workspace;

    @Test
    void paginationAndRestartKeepTitlesAndTimestamps() throws Exception {
        ThreadCatalog catalog = new ThreadCatalog(workspace);
        catalog.submitted("first", "第一条消息");
        long firstCreatedAt = catalog.get("first").createdAt();
        Thread.sleep(3);
        catalog.submitted("second", "第二条消息");

        ThreadCatalog.Page firstPage = catalog.page(1, null);
        assertEquals(1, firstPage.threads().size());
        assertEquals("second", firstPage.threads().get(0).id());
        assertNotNull(firstPage.nextCursor());

        ThreadCatalog.Page secondPage = catalog.page(1, firstPage.nextCursor());
        assertEquals(1, secondPage.threads().size());
        assertEquals("first", secondPage.threads().get(0).id());
        assertNull(secondPage.nextCursor());

        ThreadCatalog reopened = new ThreadCatalog(workspace);
        assertEquals("第一条消息", reopened.get("first").title());
        assertEquals(firstCreatedAt, reopened.get("first").createdAt());
        assertEquals("second", reopened.page(1, null).threads().get(0).id());
    }

    @Test
    void staleRowsAreRemovedAndBadCursorIsRejected() {
        ThreadCatalog catalog = new ThreadCatalog(workspace);
        catalog.submitted("committed", "已提交");
        catalog.submitted("phantom", "未落盘");
        catalog.retain(Set.of("committed"));

        assertNull(catalog.get("phantom"));
        assertEquals(1, catalog.page(20, null).threads().size());
        assertFalse(catalog.get("committed").title().isBlank());
        assertThrows(IllegalArgumentException.class, () -> catalog.page(1, "bad"));
    }

    @Test void 列表翻页期间更新时间变化不会遗漏旧快照成员() throws Exception {
        var catalog = new ThreadCatalog(workspace);
        catalog.submitted("first", "第一条"); Thread.sleep(3);
        catalog.submitted("second", "第二条"); Thread.sleep(3);
        catalog.submitted("third", "第三条");
        var page = catalog.page(1, null);
        assertEquals("third", page.threads().get(0).id());
        catalog.submitted("first", "更新第一条");
        var next = catalog.page(1, page.nextCursor());
        var last = catalog.page(1, next.nextCursor());
        assertEquals("second", next.threads().get(0).id());
        assertEquals("first", last.threads().get(0).id());
    }

    @Test
    void displaySnapshotSurvivesRestartAndRejectsOldLogPosition() {
        ThreadCatalog catalog = new ThreadCatalog(workspace);
        ThreadCatalog.DisplaySnapshot value = new ThreadCatalog.DisplaySnapshot(12,
                List.of(new ThreadCatalog.DisplayMessage("m1", "user", "你好"),
                        new ThreadCatalog.DisplayMessage("m2", "assistant", "你好！")),
                List.of(new ThreadCatalog.DisplayTurn("r1", "finished")));
        catalog.saveDisplay("thread", value);

        ThreadCatalog reopened = new ThreadCatalog(workspace);
        assertEquals(value, reopened.display("thread", 12));
        assertNull(reopened.display("thread", 13));
        assertEquals(value, reopened.previousDisplay("thread", 13));
        assertNull(reopened.previousDisplay("thread", 12));

        reopened.saveDisplay("thread", new ThreadCatalog.DisplaySnapshot(13,
                List.of(new ThreadCatalog.DisplayMessage("m3", "user", "新消息")), List.of()));
        assertNull(reopened.display("thread", 12));
        assertEquals(1, reopened.display("thread", 13).messages().size());
        assertEquals(0, reopened.display("thread", 13).turns().size());
    }
}
