package com.beyondzy.demo.service.display;

import com.beyondzy.demo.domain.display.DisplayMessageBean;
import com.beyondzy.demo.domain.display.DisplayTurnBean;
import com.beyondzy.demo.domain.display.DisplayViewBean;
import java.util.List;

/** 协调 display_* 读写的应用服务接口。对应表族：display_head / display_message / display_turn / display_view。 */
public interface DisplayIndexService {

    /** 按期望水位读取展示快照；水位不匹配时返回 null。 */
    Snapshot readSnapshot(String threadId, long expectedSeq, boolean previous);

    /**
     * 同事务重写消息、轮次并更新水位。
     * 先删后插再写 head，保证快照三部分一起提交，避免读到半截索引。
     */
    void saveSnapshot(String threadId, long seq, List<Message> messages, List<Turn> turns);

    /** 读取指定水位的完整视图缓存。 */
    DisplayViewBean findView(String threadId, long seq);

    /** 保存或条件更新完整视图缓存。 */
    void saveView(String threadId, long seq, int format, boolean hasInteractions, String payload);

    record Snapshot(long seq, List<DisplayMessageBean> messages, List<DisplayTurnBean> turns) {}
    record Message(String messageId, String role, String content) {}
    record Turn(String runId, String status) {}
}
