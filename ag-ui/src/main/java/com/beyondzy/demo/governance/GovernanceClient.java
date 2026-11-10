package com.beyondzy.demo.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 治理 HTTP 客户端。tenant/app/subject 由服务端根据令牌决定，请求体不携带这些字段。
 * 不重试。invoke 的传输失败返回不确定的传输结果，不伪装成业务成功。
 */
public final class GovernanceClient implements GovernanceGateway {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final URI baseUri;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;

    public GovernanceClient(String baseUrl, String token) {
        this(baseUrl, token, Duration.ofSeconds(20));
    }

    public GovernanceClient(String baseUrl, String token, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("治理地址不能为空");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Agent token 不能为空");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("超时必须大于 0");
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        this.baseUri = URI.create(trimmed);
        this.token = token.trim();
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static GovernanceClient ifConfigured(String baseUrl, String token) {
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) return null;
        return new GovernanceClient(baseUrl, token);
    }

    @Override
    public GovernanceSnapshot currentSnapshot() {
        Exchange exchange = send("GET", "/api/agent/snapshot/current", null);
        if (exchange.transportError() != null) {
            throw new IllegalStateException("拉取治理 Snapshot 失败: " + exchange.transportError());
        }
        if (exchange.status() == 401) throw new IllegalStateException("治理鉴权失败");
        JsonNode body = exchange.body();
        if (exchange.status() != 200 || body == null || !body.hasNonNull("snapshotId")) {
            throw new IllegalStateException("拉取治理 Snapshot 失败: HTTP " + exchange.status());
        }
        return parseSnapshot(body);
    }

    @Override
    public GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments) {
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("uri 不能为空");
        if (snapshotId == null || snapshotId.isBlank()) throw new IllegalArgumentException("snapshotId 不能为空");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uri", uri);
        body.put("snapshotId", snapshotId);
        body.put("arguments", GovernanceArguments.copy(arguments));
        Exchange exchange = send("POST", "/api/agent/invoke", body);
        if (exchange.transportError() != null) {
            logger.warn("治理 invoke 传输失败: {}", exchange.transportError());
            return GovernanceInvokeResult.transport("TRANSPORT_FAILURE", "治理服务不可达");
        }
        return parseInvoke(exchange.status(), exchange.body());
    }

    private Exchange send(String method, String path, Map<String, Object> body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(timeout)
                    .header("X-Agent-Token", token)
                    .header("Accept", "application/json")
                    .header("X-Trace-Id", UUID.randomUUID().toString());
            // snapshot 可不带用户头；invoke（及需要真实用户连接检查的调用）必须带提交时捕获的会话凭证。
            boolean requireTrustedUser = body != null;
            if (requireTrustedUser) {
                String userCredential = OutboundUserCredential.current()
                        .orElseThrow(() -> new IllegalStateException("缺少出站用户会话凭证；请在任务提交时绑定会话"));
                request.header("X-Trusted-User-Credential", userCredential);
                request.header("X-Trusted-User-Credential-Type", "opaque_session");
            }
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return Exchange.of(response.statusCode(), parseJson(response.body()));
        } catch (IllegalStateException error) {
            return Exchange.failed(error.getMessage());
        } catch (IOException error) {
            return Exchange.failed(error.getClass().getSimpleName());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Exchange.failed("interrupted");
        }
    }

    private static GovernanceSnapshot parseSnapshot(JsonNode body) {
        List<GovernanceToolView> tools = new ArrayList<>();
        JsonNode listed = body.path("tools");
        if (listed.isArray()) {
            for (JsonNode tool : listed) {
                JsonNode schema = tool.get("inputSchema");
                Map<String, Object> inputSchema = schema != null && schema.isObject()
                        ? JSON.convertValue(schema, MAP)
                        : null;
                tools.add(new GovernanceToolView(
                        text(tool, "uri"),
                        text(tool, "name"),
                        text(tool, "description"),
                        inputSchema,
                        text(tool, "bindingId"),
                        text(tool, "cardId"),
                        text(tool, "cardVersion"),
                        text(tool, "riskLabel")));
            }
        }
        return new GovernanceSnapshot(
                text(body, "tenantId"),
                text(body, "appId"),
                text(body, "subject"),
                text(body, "snapshotId"),
                tools);
    }

    private static GovernanceInvokeResult parseInvoke(int status, JsonNode body) {
        if (body != null && body.hasNonNull("outcome") && body.hasNonNull("code")) {
            String outcome = body.get("outcome").asText();
            GovernanceInvokeResult.Kind kind = switch (outcome) {
                case "completed" -> GovernanceInvokeResult.Kind.COMPLETED;
                case "rejected" -> GovernanceInvokeResult.Kind.REJECTED;
                case "failed" -> GovernanceInvokeResult.Kind.FAILED;
                case "uncertain" -> GovernanceInvokeResult.Kind.UNCERTAIN;
                default -> GovernanceInvokeResult.Kind.TRANSPORT;
            };
            JsonNode resultNode = body.get("result");
            Map<String, Object> result = resultNode != null && resultNode.isObject()
                    ? JSON.convertValue(resultNode, MAP)
                    : null;
            JsonNode extensionsNode = body.get("extensions");
            Map<String, Object> extensions = extensionsNode != null && extensionsNode.isObject()
                    ? JSON.convertValue(extensionsNode, MAP)
                    : Map.of();
            if (kind != GovernanceInvokeResult.Kind.COMPLETED) result = null;
            return new GovernanceInvokeResult(
                    kind,
                    text(body, "traceId"),
                    text(body, "callId"),
                    outcome,
                    text(body, "code"),
                    text(body, "message"),
                    body.path("agentMayRetry").asBoolean(false),
                    body.path("rebindRequired").asBoolean(false),
                    result,
                    extensions);
        }
        if (status == 401) {
            String message = body != null && body.hasNonNull("message")
                    ? body.get("message").asText()
                    : "无效的 Agent 凭证";
            return GovernanceInvokeResult.transport("UNAUTHENTICATED", message);
        }
        logger.warn("治理 invoke 响应无法归类, HTTP {}", status);
        return GovernanceInvokeResult.transport("TRANSPORT_FAILURE", "治理响应无法归类");
    }

    private static JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return JSON.readTree(body);
        } catch (IOException error) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private record Exchange(int status, JsonNode body, String transportError) {
        static Exchange of(int status, JsonNode body) {
            return new Exchange(status, body, null);
        }

        static Exchange failed(String error) {
            return new Exchange(0, null, error);
        }
    }
}
