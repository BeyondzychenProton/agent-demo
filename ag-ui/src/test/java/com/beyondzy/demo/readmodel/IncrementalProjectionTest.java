package com.beyondzy.demo.readmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.contract.ThreadView;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class IncrementalProjectionTest {

    @Test
    void emitsMessageAndCardLifecycleForStableKeys() {
        Msg user = Msg.builder().id("m-user").role(MsgRole.USER).textContent("查订单 A1001").build();
        List<SessionEvent> events =
                List.of(
                        event(1, "message/user", Map.of("message", user)),
                        event(
                                2,
                                "model/chunk",
                                Map.of(
                                        "modelCallId",
                                        "m1",
                                        "chunk",
                                        Map.of(
                                                "id",
                                                "m-assistant",
                                                "content",
                                                List.of(
                                                        Map.of("type", "text", "text", "好的，"),
                                                        Map.of(
                                                                "type", "tool_use",
                                                                "id", "tc1",
                                                                "name", "lookup_order",
                                                                "input", Map.of(),
                                                                "content", "{\"order_id\":\"A1001\"}"))))),
                        event(
                                3,
                                "tool/requested",
                                Map.of(
                                        "messageId", "m-assistant",
                                        "toolCallId", "tc1",
                                        "call",
                                                Map.of(
                                                        "type", "tool_use",
                                                        "id", "tc1",
                                                        "name", "lookup_order",
                                                        "input", Map.of("order_id", "A1001"),
                                                        "content",
                                                                "{\"order_id\":\"A1001\"}"))),
                        event(
                                4,
                                "tool/dispatch",
                                Map.of(
                                        "toolCallId", "tc1",
                                        "name", "lookup_order",
                                        "arguments", Map.of("order_id", "A1001"))),
                        event(5, "tool/chunk", Map.of(
                                "toolCallId", "tc1",
                                "chunk", ToolResultBlock.text("正在查询 …"))),
                        event(
                                6,
                                "action/end",
                                Map.of(
                                        "toolCallId",
                                        "tc1",
                                        "result",
                                        ToolResultBlock.text("{\"orderId\":\"A1001\"}")
                                                .withState(ToolResultState.SUCCESS))),
                        event(7, "run/end", Map.of("status", "completed")));

        List<ThreadView.IncrementalEvent> out =
                new IncrementalEventProjection().apply(events);

        ThreadView.MessageDelta userDelta =
                assertInstanceOf(ThreadView.MessageDelta.class, out.get(0));
        assertEquals("message-delta", userDelta.type());
        assertEquals("user", userDelta.role());
        assertTrue(
                out.stream()
                        .anyMatch(
                                event ->
                                        event instanceof ThreadView.MessageDelta delta
                                                && delta.partial()
                                                && "好的，".equals(delta.chunk())));
        ThreadView.CardArgs args =
                assertInstanceOf(
                        ThreadView.CardArgs.class,
                        out.stream()
                                .filter(ThreadView.CardArgs.class::isInstance)
                                .findFirst()
                                .orElseThrow());
        assertEquals("tc1", args.toolCallId());
        assertEquals("lookup_order", args.displayTitle());
        ThreadView.CardResult result =
                assertInstanceOf(ThreadView.CardResult.class,
                        out.stream()
                                .filter(ThreadView.CardResult.class::isInstance)
                                .findFirst()
                                .orElseThrow());
        assertEquals("completed", result.status());
        ThreadView.RunFinished finished =
                assertInstanceOf(
                        ThreadView.RunFinished.class, out.get(out.size() - 1));
        assertEquals("completed", finished.status());
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
