package com.beyondzy.demo.agui;

import com.beyondzy.demo.support.ContentValues;
import com.beyondzy.demo.support.Payloads;
import io.agentscope.core.agent.accumulator.ToolCallsAccumulator;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 第一跳/第二跳的最小实现：SessionLog 已提交事件 → AG-UI 标准帧。
 *
 * <p>每个 run 一个实例，负责：
 * <ul>
 *   <li>文本 chunk → TEXT_MESSAGE_STARTED / CONTENT；
 *   <li>工具参数分片 → TOOL_CALL_STARTED / ARGS（只发 delta，不重复整串）；
 *   <li>执行进度 → CUSTOM(tool-progress)，因为 AG-UI 标准没有进度帧；
 *   <li>run/end 前闭合所有 open text/tool，保证 start/end 配对。
 * </ul>
 */
public final class AguiEventProjection {

    private static final class ToolState {
        String name = "unknown";
        boolean started;
        boolean ended;
        boolean resultSent;
        int argsSent;
    }

    private final String threadId;
    private final String runId;
    private final String targetRunId;
    private final String messageId;
    private final ToolCallsAccumulator fragments = new ToolCallsAccumulator();
    private final Map<String, ToolState> tools = new LinkedHashMap<>();

    private boolean textStarted;
    private boolean chunkSent;
    private String fallbackText;

    public AguiEventProjection(String threadId, String runId, String targetRunId, String messageId) {
        this.threadId = threadId;
        this.runId = runId;
        this.targetRunId = targetRunId;
        this.messageId = messageId;
    }

    public List<AguiEvent> apply(List<SessionEvent> events) {
        List<AguiEvent> out = new ArrayList<>();
        for (SessionEvent event : events) {
            if (targetRunId != null && !targetRunId.equals(event.executionRunId())) continue;
            apply(event, out);
        }
        return out;
    }

    private void apply(SessionEvent event, List<AguiEvent> out) {
        Map<String, Object> data = event.data();
        switch (event.type()) {
            case "model/chunk" -> modelChunk(data, out);
            case "message/assistant" -> {
                if (!chunkSent) fallbackText = assistantText(data.get("message"));
            }
            case "tool/requested" -> requested(data, out);
            case "tool/dispatch" -> dispatch(data, out);
            case "tool/chunk" -> progress(data, out);
            case "action/end" -> ended(data, out);
            case "tool/result" -> resultMessage(data, out);
            case "run/end" -> finish(out);
            default -> {
                // 其余事件最小闭环不外发
            }
        }
    }

    private void modelChunk(Map<String, Object> data, List<AguiEvent> out) {
        if (!(data.get("chunk") instanceof Map<?, ?> chunk)) return;
        if (!(chunk.get("content") instanceof List<?> blocks)) return;
        for (Object value : blocks) {
            ContentBlock block = JsonUtils.getJsonCodec().convertValue(value, ContentBlock.class);
            if (block instanceof TextBlock text) {
                String delta = text.getText();
                if (delta == null || delta.isEmpty()) continue;
                if (!textStarted) {
                    out.add(new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"));
                    textStarted = true;
                }
                out.add(new AguiEvent.TextMessageContent(threadId, runId, messageId, delta));
                chunkSent = true;
            } else if (block instanceof ToolUseBlock use) {
                toolFragment(use, out);
            }
        }
    }

    private void toolFragment(ToolUseBlock use, List<AguiEvent> out) {
        fragments.add(use);
        String id =
                use.getId() == null || use.getId().isBlank()
                        ? fragments.getCurrentToolCallId()
                        : use.getId();
        if (id == null || id.isBlank()) return;
        ToolState state = tool(id);
        if (usableName(use.getName())) {
            state.name = use.getName();
        } else {
            ToolUseBlock resolved =
                    fragments.buildAllToolCalls().stream()
                            .filter(call -> id.equals(call.getId()))
                            .findFirst()
                            .orElse(null);
            if (resolved != null && usableName(resolved.getName())) state.name = resolved.getName();
        }
        String fragment = use.getContent();
        if (fragment == null && use.getInput() != null) {
            fragment = JsonUtils.getJsonCodec().toJson(use.getInput());
        }
        if (fragment != null && !fragment.isEmpty()) {
            args(id, state, fragment, out);
        } else {
            start(id, state, out);
        }
    }

    private void requested(Map<String, Object> data, List<AguiEvent> out) {
        if (data.get("call") == null) return;
        ToolUseBlock call = Payloads.toolUse(data.get("call"));
        if (call.getId() == null) return;
        ToolState state = tool(call.getId());
        if (usableName(call.getName())) state.name = call.getName();
        String content = call.getContent();
        if (content == null && call.getInput() != null) {
            content = JsonUtils.getJsonCodec().toJson(call.getInput());
        }
        if (content != null && content.length() > state.argsSent) {
            args(call.getId(), state, content.substring(Math.min(state.argsSent, content.length())), out);
        } else {
            start(call.getId(), state, out);
        }
    }

    private void dispatch(Map<String, Object> data, List<AguiEvent> out) {
        String id = string(data.get("toolCallId"));
        if (id == null) return;
        ToolState state = tool(id);
        if (data.get("name") instanceof String name && usableName(name)) state.name = name;
        start(id, state, out);
        if (state.argsSent == 0 && data.get("arguments") != null) {
            args(id, state, JsonUtils.getJsonCodec().toJson(data.get("arguments")), out);
        }
    }

    private void progress(Map<String, Object> data, List<AguiEvent> out) {
        String id = string(data.get("toolCallId"));
        if (id == null || data.get("chunk") == null) return;
        ToolResultBlock chunk = Payloads.toolResult(data.get("chunk"));
        String text = ContentValues.resultText(chunk);
        if (text == null || text.isBlank()) return;
        out.add(
                new AguiEvent.Custom(
                        threadId,
                        runId,
                        "tool-progress",
                        Map.of("toolCallId", id, "text", text)));
    }

    private void ended(Map<String, Object> data, List<AguiEvent> out) {
        String id = string(data.get("toolCallId"));
        if (id == null || data.get("result") == null) return;
        ToolResultBlock result = Payloads.toolResult(data.get("result"));
        finishTool(id, result, out);
    }

    private void resultMessage(Map<String, Object> data, List<AguiEvent> out) {
        Object value = data.get("message");
        if (value == null) return;
        Msg message = JsonUtils.getJsonCodec().convertValue(value, Msg.class);
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ToolResultBlock result) finishTool(result.getId(), result, out);
        }
    }

    private void finishTool(String id, ToolResultBlock result, List<AguiEvent> out) {
        if (id == null) return;
        ToolState state = tool(id);
        start(id, state, out);
        if (!state.ended) {
            state.ended = true;
            out.add(new AguiEvent.ToolCallEnd(threadId, runId, id));
        }
        if (state.resultSent) return;
        state.resultSent = true;
        String text = ContentValues.resultText(result);
        out.add(
                new AguiEvent.ToolCallResult(
                        threadId,
                        runId,
                        id,
                        text == null ? "" : text,
                        "tool",
                        "tool-result-" + id));
    }

    private void finish(List<AguiEvent> out) {
        if (fallbackText != null && !fallbackText.isBlank() && !chunkSent) {
            out.add(new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"));
            out.add(new AguiEvent.TextMessageContent(threadId, runId, messageId, fallbackText));
            textStarted = true;
        }
        if (textStarted) {
            out.add(new AguiEvent.TextMessageEnd(threadId, runId, messageId));
            textStarted = false;
        }
        for (Map.Entry<String, ToolState> entry : tools.entrySet()) {
            ToolState state = entry.getValue();
            start(entry.getKey(), state, out);
            if (!state.ended) {
                state.ended = true;
                out.add(new AguiEvent.ToolCallEnd(threadId, runId, entry.getKey()));
            }
        }
        out.add(new AguiEvent.RunFinished(threadId, runId));
    }

    private void start(String id, ToolState state, List<AguiEvent> out) {
        if (state.started) return;
        state.started = true;
        out.add(new AguiEvent.ToolCallStart(threadId, runId, id, state.name));
    }

    private void args(String id, ToolState state, String delta, List<AguiEvent> out) {
        start(id, state, out);
        if (delta == null || delta.isEmpty()) return;
        state.argsSent += delta.length();
        out.add(new AguiEvent.ToolCallArgs(threadId, runId, id, delta));
    }

    private ToolState tool(String id) {
        return tools.computeIfAbsent(id, key -> new ToolState());
    }

    private static String assistantText(Object value) {
        if (value == null) return null;
        Msg message = Payloads.message(value);
        return message.getTextContent();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean usableName(String name) {
        return name != null && !name.isBlank() && !name.startsWith("__");
    }
}
