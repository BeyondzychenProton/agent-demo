package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.domain.readmodel.ReadModelChangeRowBean;
import com.beyondzy.demo.domain.readmodel.ReadModelContentBlockBean;
import com.beyondzy.demo.domain.readmodel.ReadModelHeadBean;
import java.util.List;

/** 单测用空读模型：不碰 MySQL，满足 ChatSessions 构造依赖。 */
public enum NoopReadModel implements ReadModelPersistenceService {
    INSTANCE;

    @Override
    public ReadModelHeadBean state(String thread) {
        return null;
    }

    @Override
    public String raw(String thread, String id) {
        return null;
    }

    @Override
    public Long order(String thread, String id) {
        return null;
    }

    @Override
    public long maxOrder(String thread) {
        return 0;
    }

    @Override
    public List<String> pending(String thread, long after, int limit) {
        return List.of();
    }

    @Override
    public int pendingCount(String thread) {
        return 0;
    }

    @Override
    public void invalidate(String thread) {}

    @Override
    public String findCurrentPayload(String thread, String id) {
        return null;
    }

    @Override
    public void commit(CommitBatch batch) {}

    @Override
    public List<String> findPagePayloads(
            String thread, long revision, long before, int limit, boolean currentRevision) {
        return List.of();
    }

    @Override
    public long findMinChangeRevision(String thread) {
        return 0;
    }

    @Override
    public List<ReadModelChangeRowBean> findChangesAfter(String thread, long after, int limit) {
        return List.of();
    }

    @Override
    public List<ReadModelContentBlockBean> findContentBlocks(
            String thread, String id, long revision, String field, int after, int limit) {
        return List.of();
    }

    @Override
    public void cleanup(long changeTtlCutoff, long snapshotTtlCutoff) {}
}
