package com.beyondzy.demo.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ToolProjectionTest {

    @Test
    void emitsToolLifecycleAndClosesBeforeRunFinished() {
        List<SessionEvent> events =
                List.of(
                        event(
                                1,
                                "model/chunk",
                                Map.of(
                                        "modelCallId",
                                        "m1",
                                        "chunk",
                                        Map.of(
                                                "id",
                                                "msg1",
                                                "content",
                                                List.of(
                                                        toolUse(
                                                                "tc1",
                                                                "lookup_order",
                                                                "{\"order_id\":\"A1001\"}"))))),
                        event(
                                2,
                                "tool/dispatch",
                                Map.of(
                                        "toolCallId", "tc1",
                                        "name", "lookup_order",
                                        "arguments", Map.of("order_id", "A1001"))),
                        event(3, "tool/chunk", Map.of("toolCallId", "tc1",
                                "chunk", ToolResultBlock.text("正在查询 …"))),
                        event(4, "action/end", Map.of("toolCallId", "tc1",
                                "result", ToolResultBlock.text("{\"orderId\":\"A1001\"}")
                                        .withState(ToolResultState.SUCCESS))),
                        event(5, "run/end", Map.of("status", "completed")));

        AguiEventProjection projection =
                new AguiEventProjection("t1", "agui-run", "real-run", "msg1");
        List<AguiEvent> frames = projection.apply(events);

        AguiEvent.ToolCallStart start =
                assertInstanceOf(AguiEvent.ToolCallStart.class, frames.get(0));
        assertEquals("tc1", start.toolCallId());
        assertEquals("lookup_order", start.toolCallName());
        assertTrue(frames.stream().anyMatch(AguiEvent.ToolCallArgs.class::isInstance));
        assertTrue(frames.stream().anyMatch(AguiEvent.Custom.class::isInstance));
        assertTrue(frames.stream().anyMatch(AguiEvent.ToolCallEnd.class::isInstance));
        assertTrue(frames.stream().anyMatch(AguiEvent.ToolCallResult.class::isInstance));
        assertInstanceOf(AguiEvent.RunFinished.class, frames.get(frames.size() - 1));
    }

    @Test
    void doesNotRepeatArgumentsWhenFragmentsAndDispatchBothArrive() {
        List<SessionEvent> events =
                List.of(
                        event(
                                1,
                                "model/chunk",
                                Map.of(
                                        "modelCallId",
                                        "m1",
                                        "chunk",
                                        Map.of(
                                                "id",
                                                "msg1",
                                                "content",
                                                List.of(
                                                        toolUse(
                                                                "tc1",
                                                                "lookup_order",
                                                                "{\"order_id\":\"A1001\"}"))))),
                        event(
                                2,
                                "tool/dispatch",
                                Map.of(
                                        "toolCallId", "tc1",
                                        "name", "lookup_order",
                                        "arguments", Map.of("order_id", "A1001"))),
                        event(3, "run/end", Map.of("status", "completed")));

        List<AguiEvent> frames =
                new AguiEventProjection("t1", "agui-run", "real-run", "msg1").apply(events);
        long argsFrames =
                frames.stream().filter(AguiEvent.ToolCallArgs.class::isInstance).count();
        assertEquals(1, argsFrames);
        assertInstanceOf(AguiEvent.RunFinished.class, frames.get(frames.size() - 1));
    }

    private static Map<String, Object> toolUse(String id, String name, String content) {
        return Map.of(
                "type",
                "tool_use",
                "id",
                id,
                "name",
                name,
                "input",
                Map.of("order_id", "A1001"),
                "content",
                content);
    }

    private static SessionEvent event(long seq, String type, Map<String, Object> data) {
        return new SessionEvent(
                1,
                "e" + seq,
                seq,
                seq,
                type,
                "real-run",
                "turn1",
                false,
                JsonUtils.getJsonCodec().toJson(data));
    }
}
