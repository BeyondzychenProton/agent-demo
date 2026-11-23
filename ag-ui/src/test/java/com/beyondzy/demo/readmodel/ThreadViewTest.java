package com.beyondzy.demo.readmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.beyondzy.demo.contract.ThreadView;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ThreadViewTest {

    @Test
    void aggregatesOrderCardWithTitleStatusAndResult() {
        List<SessionEvent> facts =
                List.of(
                        event(1, "model/chunk", toolChunk("lookup_order", "{\"order_id\":\"A1001\"}")),
                        event(2, "tool/dispatch", dispatch("lookup_order", Map.of("order_id", "A1001"))),
                        event(
                                3,
                                "action/end",
                                Map.of(
                                        "toolCallId",
                                        "tc1",
                                        "result",
                                        ToolResultBlock.text(
                                                        "{\"orderId\":\"A1001\",\"status\":\"已发货\"}")
                                                .withState(ToolResultState.SUCCESS))),
                        event(4, "run/end", Map.of("status", "completed")));

        ThreadView.SnapshotResponse response = assemble(facts);
        ThreadView.ToolCardViewModel card =
                assertInstanceOf(ThreadView.ToolCardViewModel.class, response.view().get(0));

        assertEquals("tool-card", card.kind());
        assertEquals("tc1", card.toolCallId());
        assertEquals("lookup_order", card.name());
        assertEquals("lookup_order", card.displayTitle());
        assertEquals("completed", card.status());
        assertInstanceOf(Map.class, card.result());
        assertEquals("已发货", ((Map<?, ?>) card.result()).get("status"));
        assertNotNull(card.startedAt());
        assertNotNull(card.endedAt());
    }

    @Test
    void assemblesToolCardWithoutCatalogMetadata() {
        List<SessionEvent> facts =
                List.of(
                        event(1, "model/chunk", toolChunk("write_note", "{\"path\":\"notes/a.md\"}")),
                        event(2, "tool/dispatch", dispatch("write_note", Map.of("path", "notes/a.md"))),
                        event(
                                3,
                                "action/end",
                                Map.of(
                                        "toolCallId",
                                        "tc1",
                                        "result",
                                        ToolResultBlock.text(
                                                        "{\"path\":\"notes/a.md\",\"added\":3,\"deleted\":1}")
                                                .withState(ToolResultState.SUCCESS))),
                        event(4, "run/end", Map.of("status", "completed")));

        ThreadView.ToolCardViewModel card =
                assertInstanceOf(
                        ThreadView.ToolCardViewModel.class, assemble(facts).view().get(0));

        assertNull(card.diffStats());
        assertEquals("write_note", card.displayTitle());
    }

    private static ThreadView.SnapshotResponse assemble(List<SessionEvent> facts) {
        ChatHistory.Snapshot snapshot =
                new ChatHistory.Snapshot(
                        "t1",
                        "t1:9",
                        List.of(),
                        ChatItems.read(facts),
                        List.of(),
                        List.of(),
                        0,
                        null,
                        false);
        return ThreadViewAssembler.assemble("t1", snapshot);
    }

    private static Map<String, Object> toolChunk(String name, String args) {
        return Map.of(
                "modelCallId",
                "m1",
                "chunk",
                Map.of(
                        "id",
                        "msg1",
                        "content",
                        List.of(
                                Map.of(
                                        "type", "tool_use",
                                        "id", "tc1",
                                        "name", name,
                                        "input", Map.of(),
                                        "content", args))));
    }

    private static Map<String, Object> dispatch(String name, Map<String, Object> args) {
        return Map.of("toolCallId", "tc1", "name", name, "arguments", args);
    }

    private static SessionEvent event(long seq, String type, Map<String, Object> data) {
        return new SessionEvent(
                1,
                "e" + seq,
                seq,
                seq * 1000,
                type,
                "run1",
                "turn1",
                false,
                JsonUtils.getJsonCodec().toJson(data));
    }
}
