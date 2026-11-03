package com.beyondzy.demo.service;

import com.beyondzy.demo.domain.DisplayMessageBean;
import com.beyondzy.demo.domain.DisplayTurnBean;
import com.beyondzy.demo.domain.DisplayViewBean;
import com.beyondzy.demo.mapper.DisplayMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 协调 display_* 读写，并保证消息、轮次与水位保持同一事务。 */
@Service
public class DisplayIndexService {
    private final DisplayMapper mapper;

    public DisplayIndexService(DisplayMapper mapper) { this.mapper = mapper; }

    @Transactional(readOnly = true)
    public Snapshot readSnapshot(String threadId, long expectedSeq, boolean previous) {
        Long savedSeq = mapper.findSnapshotSeq(threadId);
        if (savedSeq == null || (previous ? savedSeq >= expectedSeq : savedSeq != expectedSeq)) return null;
        return new Snapshot(savedSeq, mapper.findMessages(threadId), mapper.findTurns(threadId));
    }

    @Transactional
    public void saveSnapshot(String threadId, long seq, List<Message> messages, List<Turn> turns) {
        mapper.deleteMessages(threadId);
        mapper.deleteTurns(threadId);
        for (int i = 0; i < messages.size(); i++) {
            Message item = messages.get(i);
            mapper.insertMessage(threadId, i, item.messageId(), item.role(), item.content());
        }
        for (int i = 0; i < turns.size(); i++) {
            Turn item = turns.get(i);
            mapper.insertTurn(threadId, i, item.runId(), item.status());
        }
        mapper.saveSnapshotSeq(threadId, seq);
    }

    @Transactional(readOnly = true)
    public DisplayViewBean findView(String threadId, long seq) { return mapper.findView(threadId, seq); }

    @Transactional
    public void saveView(String threadId, long seq, int format, boolean hasInteractions, String payload) {
        mapper.saveView(threadId, seq, format, hasInteractions, payload);
    }

    public record Snapshot(long seq, List<DisplayMessageBean> messages, List<DisplayTurnBean> turns) {}
    public record Message(String messageId, String role, String content) {}
    public record Turn(String runId, String status) {}
}
