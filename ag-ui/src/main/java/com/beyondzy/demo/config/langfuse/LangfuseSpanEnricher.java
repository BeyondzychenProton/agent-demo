package com.beyondzy.demo.config.langfuse;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.util.JsonUtils;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

/**
 * 跑在 {@code OtelTracingMiddleware} 内层，把用户、会话、完整 prompt 和工具结果写到当前 span。
 * 属性名按 Langfuse 的优先级：{@code langfuse.observation.input/output} 高于通用 GenAI 字段。
 */
public final class LangfuseSpanEnricher implements MiddlewareBase {
    private static final Logger logger = LoggerFactory.getLogger(LangfuseSpanEnricher.class);

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT, ExtensionPoint.ON_MODEL_CALL, ExtensionPoint.ON_ACTING);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(view -> {
            Span span = currentSpan(view);
            applyRunAttributes(span, ctx);
            attribute(span, "langfuse.observation.type", "agent");
            attribute(span, "langfuse.observation.input", messagesJson(input == null ? null : input.msgs()));
            return next.apply(input).doOnNext(event -> {
                if (event instanceof AgentResultEvent result) {
                    attribute(span, "langfuse.observation.output", messageJson(result.getResult()));
                }
            });
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(view -> {
            Span span = currentSpan(view);
            applyRunAttributes(span, ctx);
            attribute(span, "langfuse.observation.type", "generation");
            attribute(span, "langfuse.observation.input", messagesJson(input == null ? null : input.messages()));
            ModelCapture capture = new ModelCapture();
            return next.apply(input).doOnNext(event -> {
                if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                    capture.text.append(delta.getDelta());
                    attribute(span, "langfuse.observation.output", capture.json());
                } else if (event instanceof ToolCallDeltaEvent delta) {
                    capture.toolDelta(delta);
                    attribute(span, "langfuse.observation.output", capture.json());
                }
            });
        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(view -> {
            Span span = currentSpan(view);
            applyRunAttributes(span, ctx);
            attribute(span, "langfuse.observation.type", "tool");
            attribute(span, "langfuse.observation.input", toolCallsJson(input));
            ToolCapture capture = new ToolCapture();
            return next.apply(input).doOnNext(event -> {
                if (event instanceof ToolResultTextDeltaEvent delta && delta.getDelta() != null) {
                    capture.text(delta.getToolCallId(), delta.getToolCallName(), delta.getDelta());
                    attribute(span, "langfuse.observation.output", capture.json());
                } else if (event instanceof ToolResultDataDeltaEvent delta && delta.getData() != null) {
                    capture.data(delta.getToolCallId(), delta.getToolCallName(), block(delta.getData()));
                    attribute(span, "langfuse.observation.output", capture.json());
                }
            });
        });
    }

    private static Span currentSpan(ContextView view) {
        Context context = ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                view, Context.current());
        return Span.fromContext(context);
    }

    private static void applyRunAttributes(Span span, RuntimeContext ctx) {
        if (ctx != null) {
            attribute(span, "user.id", ctx.getUserId());
            attribute(span, "session.id", ctx.getSessionId());
            attribute(span, "langfuse.trace.metadata.run_id", ctx.getRunId());
        }
    }

    private static void attribute(Span span, String key, String value) {
        if (span == null || value == null || !span.getSpanContext().isValid()) {
            return;
        }
        try {
            span.setAttribute(key, value);
        } catch (RuntimeException error) {
            logger.warn("Langfuse 属性 {} 写入失败：{}", key, error.toString());
        }
    }

    private static String messagesJson(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        List<Object> rows = new ArrayList<>();
        for (Msg message : messages) {
            if (message != null) {
                rows.add(messageRow(message));
            }
        }
        return json(rows);
    }

    private static String messageJson(Msg message) {
        return message == null ? null : json(messageRow(message));
    }

    private static Map<String, Object> messageRow(Msg message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", message.getRole() == null ? null : message.getRole().name());
        row.put("content", blocks(message.getContent()));
        return row;
    }

    private static String toolCallsJson(ActingInput input) {
        List<Object> rows = new ArrayList<>();
        if (input != null && input.toolCalls() != null) {
            for (ToolUseBlock call : input.toolCalls()) {
                if (call == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", call.getId());
                row.put("name", call.getName());
                row.put("input", call.getInput());
                rows.add(row);
            }
        }
        return json(rows);
    }

    private static List<Object> blocks(List<ContentBlock> content) {
        List<Object> rows = new ArrayList<>();
        if (content == null) {
            return rows;
        }
        for (ContentBlock block : content) {
            if (block != null) {
                rows.add(block(block));
            }
        }
        return rows;
    }

    private static Object block(ContentBlock block) {
        if (block instanceof TextBlock text) {
            return mapOf("type", "text", "text", text.getText());
        }
        if (block instanceof ThinkingBlock thinking) {
            return mapOf("type", "thinking", "thinking", thinking.getThinking());
        }
        if (block instanceof ToolUseBlock tool) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "tool_use");
            row.put("id", tool.getId());
            row.put("name", tool.getName());
            row.put("input", tool.getInput());
            return row;
        }
        if (block instanceof ToolResultBlock tool) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "tool_result");
            row.put("id", tool.getId());
            row.put("name", tool.getName());
            row.put("output", blocks(tool.getOutput()));
            return row;
        }
        return mapOf("type", block.getClass().getSimpleName());
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(k1, v1);
        row.put(k2, v2);
        return row;
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key, value);
        return row;
    }

    private static String json(Object value) {
        try {
            return JsonUtils.getJsonCodec().toJson(value);
        } catch (RuntimeException error) {
            logger.warn("Langfuse 内容序列化失败：{}", error.toString());
            return null;
        }
    }

    private static final class ModelCapture {
        private final StringBuilder text = new StringBuilder();
        private final Map<String, ToolCall> tools = new LinkedHashMap<>();

        private void toolDelta(ToolCallDeltaEvent delta) {
            String id = delta.getToolCallId() == null ? "" : delta.getToolCallId();
            ToolCall call = tools.computeIfAbsent(id, ignored -> new ToolCall());
            if (delta.getToolCallName() != null) {
                call.name = delta.getToolCallName();
            }
            if (delta.getDelta() != null) {
                call.arguments.append(delta.getDelta());
            }
        }

        private String json() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", text.toString());
            List<Object> calls = new ArrayList<>();
            for (Map.Entry<String, ToolCall> entry : tools.entrySet()) {
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", entry.getKey());
                call.put("name", entry.getValue().name);
                call.put("arguments", entry.getValue().arguments.toString());
                calls.add(call);
            }
            row.put("toolCalls", calls);
            return LangfuseSpanEnricher.json(row);
        }
    }

    private static final class ToolCall {
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    private static final class ToolCapture {
        private final Map<String, Result> results = new LinkedHashMap<>();

        private void text(String id, String name, String delta) {
            Result result = result(id, name);
            result.text.append(delta);
        }

        private void data(String id, String name, Object block) {
            result(id, name).data.add(block);
        }

        private Result result(String id, String name) {
            String key = id == null ? "" : id;
            Result result = results.computeIfAbsent(key, ignored -> new Result());
            if (name != null) {
                result.name = name;
            }
            return result;
        }

        private String json() {
            List<Object> rows = new ArrayList<>();
            for (Map.Entry<String, Result> entry : results.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", entry.getKey());
                row.put("name", entry.getValue().name);
                row.put("text", entry.getValue().text.toString());
                row.put("data", entry.getValue().data);
                rows.add(row);
            }
            return LangfuseSpanEnricher.json(rows);
        }
    }

    private static final class Result {
        private String name;
        private final StringBuilder text = new StringBuilder();
        private final List<Object> data = new ArrayList<>();
    }
}
