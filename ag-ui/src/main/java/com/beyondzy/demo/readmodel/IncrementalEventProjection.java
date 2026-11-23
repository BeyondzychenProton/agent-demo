package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.contract.ThreadView;
import com.beyondzy.demo.support.ContentValues;
import com.beyondzy.demo.support.Payloads;
import io.agentscope.core.agent.accumulator.ToolCallsAccumulator;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * committed 事件 → 增量通道事件（大纲 6.1.8-D）。
 *
 * <p>只做翻译，不落库。前端按 toolCallId / messageId 稳定键 upsert。
 */
public final class IncrementalEventProjection {

    private static final class CardState {
        String name = "unknown";
        final StringBuilder arguments = new StringBuilder();
        int argsSent;
        boolean argsStarted;
        boolean complete;
    }

    private final Map<String, CardState> cards = new LinkedHashMap<>();
    private final Set<String> completed = new HashSet<>();
    private final ToolCallsAccumulator toolFragments = new ToolCallsAccumulator();

    public List<ThreadView.IncrementalEvent> apply(List<SessionEvent> events) {
        List<ThreadView.IncrementalEvent> out = new ArrayList<>();
        for (SessionEvent event : events) {
            apply(event, out);
        }
        return out;
    }

    private void apply(SessionEvent event, List<ThreadView.IncrementalEvent> out) {
        Map<String, Object> data = event.data();
        switch (event.type()) {
            case "message/user" -> message(event, data, out);
            case "message/assistant" -> message(event, data, out);
            case "model/chunk" -> modelChunk(event, data, out);
            case "tool/requested" -> requested(event, data, out);
            case "tool/dispatch" -> dispatch(event, data, out);
            case "tool/chunk" -> progress(event, data, out);
            case "action/end" -> ended(event, data, out);
            case "tool/result" -> toolResult(event, data, out);
            case "run/end" ->
                    out.add(
                            new ThreadView.RunFinished(
                                    "run-finished",
                                    event.executionRunId(),
                                    String.valueOf(data.get("status"))));
            case "interaction/requested" -> hitlPending(event, data, out);
            case "interaction/resolved" -> hitlDecided(event, data, out);
            default -> {
                // 其余事件最小闭环不外发
            }
        }
    }

    private void message(
            SessionEvent event, Map<String, Object> data, List<ThreadView.IncrementalEvent> out) {
        if (data.get("message") == null) return;
        Msg message = Payloads.message(data.get("message"));
        if (message.getRole() == MsgRole.SYSTEM) return;
        String text = message.getTextContent();
        if (text == null || text.isBlank()) return;
        out.add(
                new ThreadView.MessageDelta(
                        "message-delta",
                        message.getId(),
                        event.turnId(),
                        event.executionRunId(),
                        message.getRole().name().toLowerCase(),
                        text,
                        false));
    }

    private void modelChunk(
            SessionEvent event, Map<String, Object> data, List<ThreadView.IncrementalEvent> out) {
        if (!(data.get("chunk") instanceof Map<?, ?> chunk)) return;
        if (!(chunk.get("content") instanceof List<?> blocks)) return;
        String messageId = chunk.get("id") instanceof String id ? id : null;

        for (Object value : blocks) {
            ContentBlock block = JsonUtils.getJsonCodec().convertValue(value, ContentBlock.class);
            if (block instanceof TextBlock text) {
                if (messageId == null || text.getText() == null || text.getText().isEmpty()) continue;
                out.add(
                        new ThreadView.MessageDelta(
                                "message-delta",
                                messageId,
                                event.turnId(),
                                event.executionRunId(),
                                "assistant",
                                text.getText(),
                                true));
            } else if (block instanceof ToolUseBlock use) {
                toolFragment(event, use, out);
            }
        }
    }

    private void toolFragment(
            SessionEvent event, ToolUseBlock use, List<ThreadView.IncrementalEvent> out) {
        toolFragments.add(use);
        String id =
                use.getId() == null || use.getId().isBlank()
                        ? toolFragments.getCurrentToolCallId()
                        : use.getId();
        if (id == null || id.isBlank()) return;
        CardState state = card(id);
        if (usableName(use.getName())) {
            state.name = use.getName();
        } else {
            ToolUseBlock resolved =
                    toolFragments.buildAllToolCalls().stream()
                            .filter(call -> id.equals(call.getId()))
                            .findFirst()
                            .orElse(null);
            if (resolved != null && usableName(resolved.getName())) state.name = resolved.getName();
        }
        String fragment = use.getContent();
        if (fragment != null) state.arguments.append(fragment);
        // 大纲 6.1.9：前端卡片等待参数完整后再展示，不展示生成中的半成品参数。
        // 因此这里只推进状态，完整参数在 tool/requested 或 tool/dispatch 一次性下发。
        if (!state.argsStarted) {
            state.argsStarted = true;
            out.add(new ThreadView.CardStatus("card-status", id, "generating_arguments", null));
        }
    }

    private void requested(
            SessionEvent event, Map<String, Object> data, List<ThreadView.IncrementalEvent> out) {
        if (data.get("call") == null) return;
        ToolUseBlock call = Payloads.toolUse(data.get("call"));
        CardState state = card(call.getId());
        if (usableName(call.getName())) state.name = call.getName();
        String content = call.getContent();
        if (content == null && call.getInput() != null) {
            content = JsonUtils.getJsonCodec().toJson(call.getInput());
        }
        if (content != null && !content.isEmpty()) {
            state.arguments.setLength(0);
            state.arguments.append(content);
            state.argsSent = content.length();
            out.add(cardArgs(event, call.getId(), state, content));
        }
        out.add(new ThreadView.CardStatus("card-status", call.getId(), "requested", null));
    }

    private void dispatch(
            SessionEvent event, Map<String, Object> data, List<ThreadView.IncrementalEvent> out) {
        String id = string(data.get("toolCallId"));
        if (id == null) return;
        CardState state = card(id);
        if (data.get("name") instanceof String name && usableName(name)) state.name = name;
        if (state.argsSent == 0 && data.get("arguments") != null) {
            String args = JsonUtils.getJsonCodec().toJson(data.get("arguments"));
            state.arguments.append(args);
            state.argsSent = args.length();
            out.add(cardArgs(event, id, state, args));
        }
        out.add(new ThreadView.CardStatus("card-status", id, "running", null));
    }

    private void progress(
            SessionEvent event, Map<String, Object> data, List<ThreadView.IncrementalEvent> out) {
        String id = string(data.get("toolCallId"));
        if (id == null || data.get("chunk") == null) return;
        ToolResultBlock chunk = Payloads.toolResult(data.get("chunk"));
        String text = ContentValues.resultText(chunk);
        out.add(
                new ThreadView.CardStatus(
                        "card-status", id, "running", text == null || text.isBlank() ? null : text));
    }

    private void ended(
            SessionEvent event, Map<String, Object> data, List<ThreadView.IncrementalEvent> out) {
        String id = string(data.get("toolCallId"));
        if (id == null || data.get("result") == null) return;
        ToolResultBlock result = Payloads.toolResult(data.get("result"));
        cardResult(event, id, result, out);
    }

    private void toolResult(
            SessionEvent event, Map<String, Object> data, List<ThreadView.IncrementalEvent> out) {
        if (data.get("message") == null) return;
        Msg message = Payloads.message(data.get("message"));
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ToolResultBlock result) cardResult(event, result.getId(), result, out);
        }
    }

    private void cardResult(
            SessionEvent event,
            String id,
            ToolResultBlock result,
            List<ThreadView.IncrementalEvent> out) {
        if (result.isSuspended()) {
            out.add(new ThreadView.CardStatus("card-status", id, "suspended", null));
            return;
        }
        String status = ContentValues.cardStatus(result.getState().name().toLowerCase());
        // action/end 的 result 可能仍是 RUNNING，真正的终态在随后的 tool/result 消息里。
        if ("running".equals(status)) return;
        // interrupted 走 card-status（与 snapshot 的七态保持一致），card-result 只发 completed/failed。
        if ("interrupted".equals(status)) {
            out.add(new ThreadView.CardStatus("card-status", id, status, null));
            return;
        }
        if (completed.contains(id)) return;
        completed.add(id);
        CardState state = card(id);
        state.complete = true;
        String text = ContentValues.resultText(result);
        out.add(
                new ThreadView.CardResult(
                        "card-result",
                        id,
                        status,
                        ContentValues.parseJson(text),
                        "failed".equals(status) ? text : null,
                        iso(event.occurredAt())));
    }

    private void hitlPending(
            SessionEvent event, Map<String, Object> data, List<ThreadView.IncrementalEvent> out) {
        String requestId = string(data.get("requestId"));
        String toolCallId = null;
        Object payload = null;
        if (data.get("call") != null) {
            ToolUseBlock call = Payloads.toolUse(data.get("call"));
            toolCallId = call.getId();
            payload = call.getInput();
        }
        out.add(
                new ThreadView.HitlPending(
                        "hitl-pending",
                        requestId,
                        toolCallId,
                        string(data.get("kind")),
                        payload));
    }

    private void hitlDecided(
            SessionEvent event, Map<String, Object> data, List<ThreadView.IncrementalEvent> out) {
        out.add(
                new ThreadView.HitlDecided(
                        "hitl-decided",
                        string(data.get("requestId")),
                        "approved",
                        iso(event.occurredAt())));
    }

    private ThreadView.CardArgs cardArgs(
            SessionEvent event, String id, CardState state, Object chunk) {
        return new ThreadView.CardArgs(
                "card-args",
                id,
                event.turnId(),
                event.executionRunId(),
                state.name,
                state.name,
                chunk);
    }

    private CardState card(String id) {
        return cards.computeIfAbsent(id, key -> new CardState());
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean usableName(String name) {
        return name != null && !name.isBlank() && !name.startsWith("__");
    }

    private static String iso(long millis) {
        return millis <= 0 ? null : Instant.ofEpochMilli(millis).toString();
    }
}
