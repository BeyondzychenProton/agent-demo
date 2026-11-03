package com.beyondzy.demo.service;

import com.beyondzy.demo.domain.readmodel.ReadModelHeadBean;
import com.beyondzy.demo.mapper.readmodel.ReadModelMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 读模型持久化应用服务，封装 rm_* 查询和单会话清理事务。 */
@Service
public class ReadModelPersistenceService {
    private final ReadModelMapper mapper;
    public ReadModelPersistenceService(ReadModelMapper mapper) { this.mapper = mapper; }

    @Transactional(readOnly = true)
    public ReadModelHeadBean state(String thread) { return mapper.findHead(thread); }
    @Transactional(readOnly = true)
    public String raw(String thread, String id) { return mapper.findRaw(thread, id); }
    @Transactional(readOnly = true)
    public Long order(String thread, String id) { return mapper.findCurrentOrder(thread, id); }
    @Transactional(readOnly = true)
    public long maxOrder(String thread) { return mapper.findMaxOrder(thread); }
    @Transactional(readOnly = true)
    public List<String> pending(String thread, long after, int limit) {
        return mapper.findPendingPayloads(thread, after, Math.max(1, Math.min(limit, 50)));
    }
    @Transactional(readOnly = true)
    public int pendingCount(String thread) { return mapper.countPending(thread); }
    @Transactional
    public void invalidate(String thread) {
        mapper.deleteContent(thread);
        mapper.deleteChanges(thread);
        mapper.deletePending(thread);
        mapper.deleteItems(thread);
        mapper.deleteRaw(thread);
        mapper.deleteHead(thread);
    }
}
