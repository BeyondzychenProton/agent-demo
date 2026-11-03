package com.beyondzy.demo.service.readmodel.impl;

import com.beyondzy.demo.domain.readmodel.ReadModelChangeRowBean;
import com.beyondzy.demo.domain.readmodel.ReadModelContentBlockBean;
import com.beyondzy.demo.domain.readmodel.ReadModelHeadBean;
import com.beyondzy.demo.domain.readmodel.ReadModelItemPayloadBean;
import com.beyondzy.demo.domain.readmodel.ReadModelStaleItemBean;
import com.beyondzy.demo.mapper.readmodel.ReadModelMapper;
import com.beyondzy.demo.service.readmodel.CommitBatch;
import com.beyondzy.demo.service.readmodel.ReadModelPersistenceService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ReadModelPersistenceService 实现：委托 ReadModelMapper，并保证 commit/invalidate/cleanup 同事务。 */
@Service
public class ReadModelPersistenceServiceImpl implements ReadModelPersistenceService {
    private final ReadModelMapper mapper;

    public ReadModelPersistenceServiceImpl(ReadModelMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ReadModelHeadBean state(String thread) {
        return mapper.findHead(thread);
    }

    @Override
    @Transactional(readOnly = true)
    public String raw(String thread, String id) {
        return mapper.findRaw(thread, id);
    }

    @Override
    @Transactional(readOnly = true)
    public Long order(String thread, String id) {
        return mapper.findCurrentOrder(thread, id);
    }

    @Override
    @Transactional(readOnly = true)
    public long maxOrder(String thread) {
        return mapper.findMaxOrder(thread);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pending(String thread, long after, int limit) {
        return mapper.findPendingPayloads(thread, after, Math.max(1, Math.min(limit, 50)));
    }

    @Override
    @Transactional(readOnly = true)
    public int pendingCount(String thread) {
        return mapper.countPending(thread);
    }

    @Override
    @Transactional
    public void invalidate(String thread) {
        mapper.deleteContent(thread);
        mapper.deleteChanges(thread);
        mapper.deletePending(thread);
        mapper.deleteItems(thread);
        mapper.deleteRaw(thread);
        mapper.deleteHead(thread);
    }

    @Override
    @Transactional(readOnly = true)
    public String findCurrentPayload(String thread, String id) {
        ReadModelItemPayloadBean row = mapper.findCurrentPayload(thread, id);
        return row == null ? null : row.getPayload();
    }

    @Override
    @Transactional
    public void commit(CommitBatch batch) {
        CommitBatch.HeadWrite head = batch.head();
        String thread = head.thread();
        long createdAt = head.updatedAt();
        for (CommitBatch.RawWrite raw : batch.raws()) {
            mapper.upsertRaw(thread, raw.id(), raw.payload());
        }
        for (CommitBatch.ItemWrite item : batch.items()) {
            mapper.closeItemVersion(thread, item.id(), item.revision());
            mapper.insertItem(thread, item.id(), item.revision(), item.orderKey(), item.payload(), createdAt);
            mapper.insertChange(thread, item.revision(), item.id(), createdAt);
            mapper.deletePendingItem(thread, item.id());
            if (item.pendingOrderKeyOrNull() != null) {
                mapper.insertPending(thread, item.id(), item.pendingOrderKeyOrNull());
            }
            for (CommitBatch.BlockWrite block : item.blocks()) {
                mapper.insertBlockIgnore(block.hash(), block.body());
                mapper.insertContent(thread, item.id(), item.revision(), block.field(),
                        block.blockNo(), block.hash(), block.encoding());
            }
        }
        mapper.upsertHead(thread, head.generation(), head.revision(), head.execSeq(), head.inboxSeq(),
                head.updatedAt(), head.leaseTag(), head.checkpoint(), head.control());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findPagePayloads(String thread, long revision, long before, int limit, boolean currentRevision) {
        return currentRevision
                ? mapper.findPageCurrent(thread, revision, before, limit)
                : mapper.findPageAtRevision(thread, revision, before, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public long findMinChangeRevision(String thread) {
        Long minimum = mapper.findMinChangeRevision(thread);
        return minimum == null ? 0L : minimum;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReadModelChangeRowBean> findChangesAfter(String thread, long after, int limit) {
        return mapper.findChangesAfter(thread, after, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReadModelContentBlockBean> findContentBlocks(
            String thread, String id, long revision, String field, int after, int limit) {
        return mapper.findContentBlocks(thread, id, revision, field, after, limit);
    }

    @Override
    @Transactional
    public void cleanup(long changeTtlCutoff, long snapshotTtlCutoff) {
        mapper.deleteChangesBefore(changeTtlCutoff);
        List<ReadModelStaleItemBean> stale = mapper.findStaleItems(changeTtlCutoff, snapshotTtlCutoff);
        for (ReadModelStaleItemBean item : stale) {
            mapper.deleteItemVersion(item.getThread(), item.getId(), item.getRevision());
        }
        mapper.deleteOrphanContent();
        mapper.deleteOrphanBlocks();
    }
}
