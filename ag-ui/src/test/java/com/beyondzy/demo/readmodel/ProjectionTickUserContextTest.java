package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.service.readmodel.ReadModelPersistenceService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.exception.session.SessionNotFoundException;
import com.beyondzy.demo.contract.UserContext;
import com.beyondzy.demo.domain.readmodel.ReadModelHeadBean;
import com.beyondzy.demo.support.DbTimes;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 回归：后台投影 tick 必须沿用 watch 时的 UserContext，
 * 否则会落到种子 userId 并触发 SessionNotFoundException，syncState 长期 syncing。
 */
class ProjectionTickUserContextTest {

    @Test
    void backgroundTickPropagatesWatchUserContext() throws Exception {
        var seenUserIds = new CopyOnWriteArrayList<String>();
        var opens = new AtomicInteger();
        SessionLog empty = new EmptyLog();
        var persistence = new IdlePersistence();

        try (var projection = new ProjectionService(thread -> {
            String userId = UserContext.current().orElse(null);
            seenUserIds.add(userId);
            opens.incrementAndGet();
            if (!"42".equals(userId))
                throw new SessionNotFoundException("会话不存在");
            return empty;
        }, persistence)) {
            UserContext.run("42", () -> projection.watch("thr-owned"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (opens.get() < 2 && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(opens.get() >= 2, "后台 tick 应再次打开日志");

            ProjectionService.Status status = null;
            while (System.nanoTime() < deadline) {
                status = projection.status("thr-owned");
                if ("ready".equals(status.syncState())) break;
                Thread.sleep(50);
            }
            assertEquals("ready", status.syncState());
            assertTrue(seenUserIds.stream().skip(1).allMatch("42"::equals),
                    "tick 打开日志时必须带 watch 用户，实际=" + seenUserIds);
        }
    }

    private static final class EmptyLog implements SessionLog {
        private final EmptyLog inbox = this;
        EmptyLog() {}
        @Override public SessionLog inbox() { return inbox == this ? this : inbox; }
        @Override public Head head() { return new Head(0, 0, null, 0, null); }
        @Override public List<SessionEvent> readAfter(long after, int limit) { return List.of(); }
        @Override public List<SessionEvent> scan(long after, long to) { return List.of(); }
        @Override public Writer acquire(String owner, Duration lease) {
            throw new UnsupportedOperationException();
        }
        @Override public void renew(Writer writer, Duration lease) {
            throw new UnsupportedOperationException();
        }
        @Override public void release(Writer writer) {
            throw new UnsupportedOperationException();
        }
        @Override public Head commit(Writer writer, String batch, long seq, List<SessionEvent> events) {
            throw new UnsupportedOperationException();
        }
    }

    /** 已有匹配水位的读模型，使 sync 在更新 observation 后提前返回。 */
    private static final class IdlePersistence implements ReadModelPersistenceService {
        @Override public ReadModelHeadBean state(String thread) {
            var row = new ReadModelHeadBean();
            row.setGeneration("g");
            row.setRevision(1L);
            row.setExecSeq(0L);
            row.setInboxSeq(0L);
            row.setGmtModified(DbTimes.now());
            row.setLeaseTag(":false");
            row.setCheckpoint("{}");
            row.setControl("{\"facts\":[],\"inputs\":[],\"commands\":{\"commands\":[],\"runEnds\":{}}}");
            return row;
        }
        @Override public String raw(String thread, String id) { return null; }
        @Override public Long order(String thread, String id) { return null; }
        @Override public long maxOrder(String thread) { return 0; }
        @Override public List<String> pending(String thread, long after, int limit) { return List.of(); }
        @Override public int pendingCount(String thread) { return 0; }
        @Override public void invalidate(String thread) {}
        @Override public String findCurrentPayload(String thread, String id) { return null; }
        @Override public void commit(CommitBatch batch) {}
        @Override public List<String> findPagePayloads(
                String thread, long revision, long before, int limit, boolean currentRevision) {
            return List.of();
        }
        @Override public long findMinChangeRevision(String thread) { return 0; }
        @Override public List<ReadModelChangeRowBean> findChangesAfter(String thread, long after, int limit) {
            return List.of();
        }
        @Override public List<ReadModelContentBlockBean> findContentBlocks(
                String thread, String id, long revision, String field, int after, int limit) {
            return List.of();
        }
        @Override public void cleanup(long changeTtlCutoff, long snapshotTtlCutoff) {}
    }
}
