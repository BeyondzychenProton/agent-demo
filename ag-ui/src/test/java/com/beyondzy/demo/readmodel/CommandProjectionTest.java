package com.beyondzy.demo.readmodel;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInbox;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 回执不代表完成，测试无活动run的排队阶段与跨run审批续跑。 */
class CommandProjectionTest {
    private SessionEvent event(String type, String run, Object payload) {
        return new SessionEvent(1, "event", 1, 1, type, run, "turn", true, ReadModelStore.json(payload));
    }
    private SessionEvent accepted(String id, String kind) {
        return event("inbox/accepted", null, new SessionInbox.Command(id, kind, "turn", List.of(), List.of()));
    }

    @Test void 已接收与执行收尾都不能被视为完成() {
        var projection = new CommandProjection(); projection.inbox(accepted("one", "submit"));
        assertEquals("queued", projection.checkpoint().commands().get(0).status());
        assertTrue(projection.checkpoint().commands().get(0).busy());
        projection.inbox(event("inbox/opened", "run", Map.of()));
        projection.inbox(event("inbox/closed", "run", Map.of()));
        assertEquals("finishing", projection.checkpoint().commands().get(0).status());
        projection.execution(event("run/end", "run", Map.of("status", "completed")));
        assertEquals("completed", projection.checkpoint().commands().get(0).status());
        assertFalse(projection.checkpoint().commands().get(0).busy());
    }

    @Test void 快速执行未观察到活动租约也能从双日志确认完成() {
        var projection = new CommandProjection();
        projection.execution(event("run/end", "run", Map.of("status", "completed")));
        projection.inbox(accepted("one", "submit")); projection.inbox(event("inbox/opened", "run", Map.of()));
        var checkpoint = ReadModelStore.decode(ReadModelStore.json(projection.checkpoint()), CommandProjection.Checkpoint.class);
        var restored = new CommandProjection(); restored.restore(checkpoint);
        assertEquals("completed", restored.checkpoint().commands().get(0).status());
    }

    @Test void 审批续跑不覆盖原命令执行编号且拒绝能收口() {
        var projection = new CommandProjection();
        projection.inbox(accepted("one", "submit")); projection.inbox(event("inbox/opened", "first", Map.of()));
        projection.execution(event("run/end", "first", Map.of("status", "suspended"))); projection.checkpoint();
        projection.inbox(accepted("answer", "respond")); projection.inbox(event("inbox/opened", "next", Map.of()));
        assertEquals("first", projection.checkpoint().commands().get(0).runId());
        assertEquals("next", projection.checkpoint().commands().get(1).runId());
        projection.inbox(event("inbox/rejected", null, Map.of("commandId", "answer", "reason", "状态冲突")));
        assertEquals("rejected", projection.checkpoint().commands().get(1).status());
        assertFalse(projection.checkpoint().commands().get(1).busy());
    }
}
