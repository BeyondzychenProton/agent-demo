package com.beyondzy.demo.display.impl;

import com.beyondzy.demo.domain.display.DisplayViewBean;
import com.beyondzy.demo.mapper.display.DisplayMapper;
import com.beyondzy.demo.display.DisplayIndexService;
import com.beyondzy.demo.support.DbTimes;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DisplayIndexService 实现：委托 DisplayMapper，并保证快照写入同事务。 */
@Service
public class DisplayIndexServiceImpl implements DisplayIndexService {
    private final DisplayMapper mapper;

    public DisplayIndexServiceImpl(DisplayMapper mapper) { this.mapper = mapper; }

    @Override
    @Transactional(readOnly = true)
    public Snapshot readSnapshot(String threadId, long expectedSeq, boolean previous) {
        Long savedSeq = mapper.findSnapshotSeq(threadId);
        if (savedSeq == null || (previous ? savedSeq >= expectedSeq : savedSeq != expectedSeq)) return null;
        return new Snapshot(savedSeq, mapper.findMessages(threadId), mapper.findTurns(threadId));
    }

    @Override
    @Transactional
    public void saveSnapshot(String threadId, long seq, List<Message> messages, List<Turn> turns) {
        // 同事务：删消息/轮次 → 重插 → 更新水位，避免半截快照对外可见。
        LocalDateTime now = DbTimes.now();
        mapper.deleteMessages(threadId);
        mapper.deleteTurns(threadId);
        for (int i = 0; i < messages.size(); i++) {
            Message item = messages.get(i);
            mapper.insertMessage(threadId, i, item.messageId(), item.role(), item.content(), now);
        }
        for (int i = 0; i < turns.size(); i++) {
            Turn item = turns.get(i);
            mapper.insertTurn(threadId, i, item.runId(), item.status(), now);
        }
        mapper.saveSnapshotSeq(threadId, seq, now);
    }

    @Override
    @Transactional(readOnly = true)
    public DisplayViewBean findView(String threadId, long seq) { return mapper.findView(threadId, seq); }

    @Override
    @Transactional
    public void saveView(String threadId, long seq, int format, boolean hasInteractions, String payload) {
        mapper.saveView(threadId, seq, format, hasInteractions, payload, DbTimes.now());
    }
}
