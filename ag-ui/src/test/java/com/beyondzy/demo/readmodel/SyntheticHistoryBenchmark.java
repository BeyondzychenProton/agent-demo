package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.session.ChatSessions;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 可重复的大日志基准，显式运行；不把首次十万事件投影混入普通单元测试。 */
public final class SyntheticHistoryBenchmark {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("target/synthetic-history-benchmark").toAbsolutePath();
        Files.createDirectories(workspace);
        var result = new LinkedHashMap<String, Object>();
        try (var sessions = new ChatSessions(workspace, new ProjectionServiceTest.ModelStub())) {
            ReadModelTestServer.seed(sessions, 1000, 98);
            result.put("sourceEvents", sessions.log("long-history").head().seq());
            long start = System.nanoTime(); var full = sessions.snapshot("long-history");
            result.put("fullSnapshotMs", elapsed(start)); result.put("messages", full.items().size());
            start = System.nanoTime(); sessions.projection().sync("long-history");
            result.put("initialProjectionMs", elapsed(start));
            var store = sessions.projection().store();
            var timings = new ArrayList<Double>(); ReadModelStore.Page first = null;
            for (int i = 0; i < 50; i++) { start = System.nanoTime(); first = store.page("long-history", 50, null); timings.add(elapsed(start)); }
            Collections.sort(timings); result.put("warmPageP95Ms", timings.get(47));
            result.put("firstPageBytes", ReadModelStore.json(first).getBytes(StandardCharsets.UTF_8).length);
            int count = first.items().size(); String cursor = first.nextCursor(); double oldest = 0;
            while (cursor != null) {
                start = System.nanoTime(); var page = store.page("long-history", 50, cursor); oldest = elapsed(start);
                count += page.items().size(); cursor = page.nextCursor();
            }
            if (count != 1000) throw new AssertionError("十万事件分页消息数不完整");
            result.put("oldestPageMs", oldest); result.put("pagedItems", count);
        }
        String report = ReadModelStore.json(result);
        Files.writeString(Path.of("target/readmodel-synthetic-benchmark.json"), report);
        System.out.println(report);
    }
    private static double elapsed(long start) { return Math.round((System.nanoTime() - start) / 1000d) / 1000d; }
}
