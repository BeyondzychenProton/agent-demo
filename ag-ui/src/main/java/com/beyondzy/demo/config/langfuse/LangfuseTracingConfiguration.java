package com.beyondzy.demo.config.langfuse;

import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 AgentScope 的 OpenTelemetry span 用 HTTP protobuf 送到 Langfuse。
 * Langfuse 不接受 gRPC。调用方持有 SDK，不注册到 GlobalOpenTelemetry。
 */
@Configuration(proxyBeanMethods = false)
public class LangfuseTracingConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(LangfuseTracingConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true")
    SdkTracerProvider langfuseTracerProvider(
            @Value("${langfuse.base-url:${langfuse.endpoint:}}") String baseUrl,
            @Value("${langfuse.public-key}") String publicKey,
            @Value("${langfuse.secret-key}") String secretKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("未配置 langfuse.base-url");
        }
        if (publicKey == null || publicKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("未配置 langfuse.public-key 或 langfuse.secret-key");
        }
        String token = Base64.getEncoder()
                .encodeToString((publicKey.trim() + ":" + secretKey.trim()).getBytes(StandardCharsets.UTF_8));
        String endpoint = tracesEndpoint(baseUrl);
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", "Basic " + token)
                .addHeader("x-langfuse-ingestion-version", "4")
                .build();
        URI destination = URI.create(endpoint);
        logger.info("Langfuse OTLP/HTTP 导出已启用，目标主机 {}，路径 {}",
                destination.getHost(), destination.getPath());
        return SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(
                        Attributes.of(AttributeKey.stringKey("service.name"), "ag-ui"))))
                // 完整 prompt 和工具结果不截断。
                .setSpanLimits(SpanLimits.builder()
                        .setMaxAttributeValueLength(Integer.MAX_VALUE)
                        .build())
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
    }

    @Bean
    LangfuseTracing langfuseTracing(
            @Qualifier("langfuseTracerProvider") ObjectProvider<SdkTracerProvider> tracerProviders) {
        SdkTracerProvider provider = tracerProviders.getIfAvailable();
        if (provider == null) {
            return LangfuseTracing.disabled();
        }
        OpenTelemetry telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        // 外层先记 span，内层再补 Langfuse 属性。
        return new LangfuseTracing(List.of(new OtelTracingMiddleware(telemetry), new LangfuseSpanEnricher()));
    }

    /** 接受站点地址、OTLP 基础地址或完整 traces 地址。 */
    static String tracesEndpoint(String configured) {
        String endpoint = configured.trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (endpoint.endsWith("/v1/traces")) {
            return endpoint;
        }
        if (endpoint.endsWith("/api/public/otel")) {
            return endpoint + "/v1/traces";
        }
        return endpoint + "/api/public/otel/v1/traces";
    }
}
