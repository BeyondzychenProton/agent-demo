package com.beyondzy.demo.readmodel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** 在权威日志的隔离副本上读取和测量；不创建 Agent，不启动任务，不访问模型。 */
public final class HistoryBenchmark {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        if (!workspace.startsWith(Path.of("target").toAbsolutePath().normalize()))
            throw new IllegalArgumentException("基准仅允许在 target 下的隔离副本运行");
        var source = new WorkspaceSessionLogStore(new LocalFilesystem(workspace));
        var runtime = RuntimeContext.builder().userId("local-user").build();
        var keys = source.list(runtime).stream().filter(key -> key.agentId().equals("session-chat")).toList();
        Function<String, SessionLog> logs = id -> source.open(new SessionKey("local-user", "session-chat", id), runtime);
        var results = new ArrayList<Map<String, Object>>(); int number = 0;
        for (var key : keys) {
            var result = new LinkedHashMap<String, Object>();
            result.put("sample", ++number); result.put("sourceEvents", logs.apply(key.sessionId()).head().seq());
            long start = System.nanoTime(); var snapshot = ChatHistory.snapshot(key.sessionId(), logs.apply(key.sessionId()));
            result.put("fullSnapshotMs", elapsed(start)); result.put("displayItems", snapshot.items().size());
            var expected = ThreadViewAssembler.assemble(key.sessionId(), snapshot);
            try (var projection = new ProjectionService(logs, workspace)) {
                start = System.nanoTime(); projection.sync(key.sessionId());
                result.put("initialProjectionMs", elapsed(start));
                var first = projection.store().page(key.sessionId(), 50, null);
                result.put("firstPageBytes", ReadModelStore.json(first).getBytes(StandardCharsets.UTF_8).length);
                int count = first.items().size(); String cursor = first.nextCursor();
                while (cursor != null) { var page = projection.store().page(key.sessionId(), 50, cursor); count += page.items().size(); cursor = page.nextCursor(); }
                if (expected.view().size() != count) throw new AssertionError("投影项数与旧视图不一致");
            }
            try (var reopened = new ProjectionService(logs, workspace)) {
                start = System.nanoTime(); reopened.store().page(key.sessionId(), 50, null);
                result.put("reopenedFirstPageMs", elapsed(start));
                var timings = new ArrayList<Double>();
                for (int i = 0; i < 50; i++) { start = System.nanoTime(); reopened.store().page(key.sessionId(), 50, null); timings.add(elapsed(start)); }
                Collections.sort(timings); result.put("warmPageP95Ms", timings.get(47));
            }
            results.add(result);
        }
        String report = ReadModelStore.json(Map.of("kind", "真实日志只读副本：函数级读取，非HTTP端到端", "samples", results));
        Files.writeString(Path.of("target/readmodel-real-benchmark.json"), report);
        System.out.println(report);
    }
    private static double elapsed(long start) { return Math.round((System.nanoTime() - start) / 1000d) / 1000d; }
}
