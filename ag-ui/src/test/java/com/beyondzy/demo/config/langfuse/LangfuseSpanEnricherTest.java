package com.beyondzy.demo.config.langfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class LangfuseSpanEnricherTest {
    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private OtelTracingMiddleware tracing;
    private LangfuseSpanEnricher enricher;
    private Agent agent;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new OtelTracingMiddleware(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
        enricher = new LangfuseSpanEnricher();
        agent = mock(Agent.class);
        when(agent.getName()).thenReturn("Session Chat");
        when(agent.getAgentId()).thenReturn("session-chat");
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void appendsLangfuseOtlpPathToBaseUrl() {
        assertEquals(
                "https://langfuse.shsets.com/api/public/otel/v1/traces",
                LangfuseTracingConfiguration.tracesEndpoint("https://langfuse.shsets.com"));
        assertEquals(
                "https://langfuse.shsets.com/api/public/otel/v1/traces",
                LangfuseTracingConfiguration.tracesEndpoint(
                        "https://langfuse.shsets.com/api/public/otel/v1/traces/"));
        assertEquals(
                "https://langfuse.shsets.com/api/public/otel/v1/traces",
                LangfuseTracingConfiguration.tracesEndpoint("https://langfuse.shsets.com/api/public/otel/"));
    }

    @Test
    void exportsSpanOverOtlpHttp() throws Exception {
        var request = new CompletableFuture<List<String>>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            try (exchange) {
                var headers = exchange.getRequestHeaders();
                request.complete(List.of(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        headers.getFirst("Content-Type"), headers.getFirst("Authorization"),
                        headers.getFirst("x-langfuse-ingestion-version"),
                        Boolean.toString(exchange.getRequestBody().readAllBytes().length > 0)));
                exchange.sendResponseHeaders(200, -1);
            }
        });
        receiver.start();
        try (var tracerProvider = new LangfuseTracingConfiguration().langfuseTracerProvider(
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/api/public/otel/",
                "public", "secret")) {
            tracerProvider.get("test").spanBuilder("chat").startSpan().end();
            assertTrue(tracerProvider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(List.of("POST", "/api/public/otel/v1/traces", "application/x-protobuf",
                    "Basic cHVibGljOnNlY3JldA==", "4", "true"), request.get(5, TimeUnit.SECONDS));
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void writesFullPromptToolResultAndIdentity() {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("user-7")
                .sessionId("session-9")
                .runId("run-3")
                .build();
        Msg prompt = Msg.builder()
                .role(MsgRole.USER)
                .textContent("查明天的会，包含未公开的议题全文")
                .build();
        Msg history = Msg.builder()
                .role(MsgRole.TOOL)
                .content(new ToolResultBlock(
                        "c0", "list_meetings", TextBlock.builder().text("明天 10:00 评审，议题全文不截断").build()))
                .build();
        Msg answer = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("明天 10:00 有评审")
                .build();

        tracing.onAgent(agent, ctx, new AgentInput(List.of(prompt)), input ->
                        enricher.onAgent(agent, ctx, input, ignored -> Flux.just(new AgentResultEvent(answer))))
                .collectList()
                .block();

        tracing.onModelCall(
                        agent,
                        ctx,
                        new ModelCallInput(List.of(prompt, history), List.of(), null, null),
                        input -> enricher.onModelCall(
                                agent,
                                ctx,
                                input,
                                ignored -> Flux.just(
                                        new TextBlockDeltaEvent("r", "b", "明天 10:00 "),
                                        new TextBlockDeltaEvent("r", "b", "有评审"))))
                .collectList()
                .block();

        tracing.onActing(
                        agent,
                        ctx,
                        new ActingInput(List.of(new ToolUseBlock(
                                "c1", "list_meetings", Map.of("day", "明天", "detail", "议题全文")))),
                        input -> enricher.onActing(
                                agent,
                                ctx,
                                input,
                                ignored -> Flux.just(new ToolResultTextDeltaEvent(
                                        "r", "c1", "list_meetings", "明天 10:00 评审，议题全文不截断"))))
                .collectList()
                .block();

        String agentInput = attribute("invoke_agent Session Chat", "langfuse.observation.input");
        String agentOutput = attribute("invoke_agent Session Chat", "langfuse.observation.output");
        String promptInput = attribute("chat unknown", "langfuse.observation.input");
        String promptOutput = attribute("chat unknown", "langfuse.observation.output");
        String toolInput = attribute("execute_tool list_meetings", "langfuse.observation.input");
        String toolOutput = attribute("execute_tool list_meetings", "langfuse.observation.output");

        assertTrue(agentInput.contains("查明天的会，包含未公开的议题全文"));
        assertTrue(agentOutput.contains("明天 10:00 有评审"));
        assertTrue(promptInput.contains("查明天的会，包含未公开的议题全文"));
        assertTrue(promptInput.contains("明天 10:00 评审，议题全文不截断"));
        assertTrue(promptOutput.contains("明天 10:00 有评审"));
        assertTrue(toolInput.contains("议题全文"));
        assertTrue(toolOutput.contains("明天 10:00 评审，议题全文不截断"));
        assertTrue("user-7".equals(attribute("invoke_agent Session Chat", "user.id")));
        assertTrue("session-9".equals(attribute("invoke_agent Session Chat", "session.id")));
        assertTrue("run-3".equals(attribute("invoke_agent Session Chat", "langfuse.trace.metadata.run_id")));
        assertTrue("agent".equals(attribute("invoke_agent Session Chat", "langfuse.observation.type")));
        assertTrue("generation".equals(attribute("chat unknown", "langfuse.observation.type")));
        assertTrue("tool".equals(attribute("execute_tool list_meetings", "langfuse.observation.type")));
    }

    private String attribute(String spanName, String key) {
        for (SpanData span : exporter.getFinishedSpanItems()) {
            if (spanName.equals(span.getName())) {
                String value = span.getAttributes().get(AttributeKey.stringKey(key));
                if (value == null) {
                    throw new AssertionError(spanName + " 缺少 " + key);
                }
                return value;
            }
        }
        throw new AssertionError("没有 span " + spanName + "，实际 "
                + exporter.getFinishedSpanItems().stream().map(SpanData::getName).toList());
    }
}
