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

    private final boolean sendTrustedIdentity;

    public static GovernanceClient forApplication(String baseUrl, String token) {
        return new GovernanceClient(baseUrl, token, Duration.ofSeconds(20), false);
    }

    public GovernanceClient(String baseUrl, String token) {
        this(baseUrl, token, Duration.ofSeconds(20));
    }

    public GovernanceClient(String baseUrl, String token, Duration timeout) {
        this(baseUrl, token, timeout, true);
    }

    private GovernanceClient(String baseUrl, String token, Duration timeout, boolean sendTrustedIdentity) {
        this.sendTrustedIdentity = sendTrustedIdentity;
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
        GovernanceSnapshotFetch fetch = fetchCurrent(null);
        if (fetch.notModified() || fetch.snapshot() == null) {
            throw new IllegalStateException("无条件拉取不应返回 304");
        }
        return fetch.snapshot();
    }

    @Override
    public GovernanceSnapshotFetch fetchCurrent(String ifNoneMatch) {
        Exchange exchange = send("GET", "/api/agent/snapshot/current", null, ifNoneMatch);
        if (exchange.transportError() != null) {
            throw new IllegalStateException("拉取治理 Snapshot 失败: " + exchange.transportError());
        }
        if (exchange.status() == 401) throw new IllegalStateException("治理鉴权失败");
        if (exchange.status() == 304) {
            String etag = firstNonBlank(exchange.etag(), stripEtag(ifNoneMatch));
            if (etag == null || etag.isBlank()) {
                throw new IllegalStateException("治理返回 304 但缺少 ETag");
            }
            return GovernanceSnapshotFetch.notModified(etag);
        }
        JsonNode body = exchange.body();
        if (exchange.status() != 200 || body == null || !body.hasNonNull("snapshotId")) {
            throw new IllegalStateException("拉取治理 Snapshot 失败: HTTP " + exchange.status());
        }
        String etag = firstNonBlank(exchange.etag(), text(body, "etag"));
        return GovernanceSnapshotFetch.of(parseSnapshot(body), etag);
    }

    @Override
    public GovernanceSnapshot fetchSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) throw new IllegalArgumentException("snapshotId 不能为空");
        Exchange exchange = send("GET", "/api/agent/snapshot/" + encodePath(snapshotId), null);
        if (exchange.transportError() != null) {
            throw new IllegalStateException("拉取历史 Snapshot 失败: " + exchange.transportError());
        }
        if (exchange.status() == 401) throw new IllegalStateException("治理鉴权失败");
        if (exchange.status() == 403) throw new IllegalStateException("无权读取该 Snapshot");
        if (exchange.status() == 404) throw new IllegalStateException("Snapshot 不存在: " + snapshotId);
        JsonNode body = exchange.body();
        if (exchange.status() != 200 || body == null || !body.hasNonNull("snapshotId")) {
            throw new IllegalStateException("拉取历史 Snapshot 失败: HTTP " + exchange.status());
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

    @Override
    public String fetchInstructions(String snapshotId, String skillId) {
        if (snapshotId == null || snapshotId.isBlank()) throw new IllegalArgumentException("snapshotId 不能为空");
        if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("skillId 不能为空");
        String path = "/api/agent/snapshot/"
                + encodePath(snapshotId)
                + "/skills/"
                + encodePath(skillId)
                + "/instructions";
        TextExchange exchange = sendText("GET", path);
        if (exchange.transportError() != null) {
            throw new IllegalStateException("拉取 Skill 正文失败: " + exchange.transportError());
        }
        if (exchange.status() == 401) throw new IllegalStateException("治理鉴权失败");
        if (exchange.status() == 403) throw new IllegalStateException("无权读取 Skill 正文");
        if (exchange.status() != 200 || exchange.body() == null) {
            throw new IllegalStateException("拉取 Skill 正文失败: HTTP " + exchange.status());
        }
        return exchange.body();
    }

    @Override
    public String fetchResource(String snapshotId, String skillId, String relativePath) {
        String path = "/api/agent/snapshot/" + encodePath(snapshotId)
                + "/skills/" + encodePath(skillId) + "/resources?path="
                + java.net.URLEncoder.encode(relativePath, java.nio.charset.StandardCharsets.UTF_8);
        TextExchange exchange = sendText("GET", path);
        if (exchange.transportError() != null) {
            throw new IllegalStateException("Skill resource unavailable: " + exchange.transportError());
        }
        if (exchange.status() != 200 || exchange.body() == null) {
            throw new IllegalStateException("Skill resource unavailable: HTTP " + exchange.status());
        }
        return exchange.body();
    }

    private Exchange send(String method, String path, Map<String, Object> body) {
        return send(method, path, body, null);
    }

    private Exchange send(String method, String path, Map<String, Object> body, String ifNoneMatch) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(timeout)
                    .header("X-Agent-Token", token)
                    .header("Accept", "application/json")
                    .header("X-Trace-Id", UUID.randomUUID().toString());
            // snapshot 可不带用户头；invoke（及需要真实用户连接检查的调用）必须带提交时捕获的会话凭证。
            boolean requireTrustedUser = body != null && sendTrustedIdentity;
            if (requireTrustedUser) {
                String userCredential = OutboundUserCredential.current()
                        .orElseThrow(() -> new IllegalStateException("缺少出站用户会话凭证；请在任务提交时绑定会话"));
                request.header("X-Trusted-User-Credential", userCredential);
                request.header("X-Trusted-User-Credential-Type", "opaque_session");
            }
            if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
                request.header("If-None-Match", quoteEtag(ifNoneMatch));
            }
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return Exchange.of(
                    response.statusCode(),
                    parseJson(response.body()),
                    stripEtag(response.headers().firstValue("ETag").orElse(null)));
        } catch (IllegalStateException error) {
            return Exchange.failed(error.getMessage());
        } catch (IOException error) {
            return Exchange.failed(error.getClass().getSimpleName());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Exchange.failed("interrupted");
        }
    }

    private static String quoteEtag(String etag) {
        String raw = stripEtag(etag);
        if (raw == null || raw.isBlank()) return etag;
        return "\"" + raw + "\"";
    }

    private static String stripEtag(String etag) {
        if (etag == null || etag.isBlank()) return null;
        String candidate = etag.trim();
        if (candidate.startsWith("W/")) candidate = candidate.substring(2).trim();
        if (candidate.length() >= 2 && candidate.startsWith("\"") && candidate.endsWith("\"")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        return candidate.isBlank() ? null : candidate;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
    }

    private TextExchange sendText(String method, String path) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(timeout)
                    .header("X-Agent-Token", token)
                    .header("Accept", "text/plain, text/markdown, application/json")
                    .header("X-Trace-Id", UUID.randomUUID().toString());
            if (!"GET".equals(method)) {
                throw new IllegalArgumentException("Skill 正文仅支持 GET");
            }
            request.GET();
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return TextExchange.of(response.statusCode(), response.body());
        } catch (IOException error) {
            return TextExchange.failed(error.getClass().getSimpleName());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return TextExchange.failed("interrupted");
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
        List<GovernanceSkillView> skills = new ArrayList<>();
        JsonNode skillNodes = body.path("skills");
        if (skillNodes.isArray()) {
            for (JsonNode skill : skillNodes) {
                List<GovernanceSkillBinding> bindings = new ArrayList<>();
                JsonNode bindingNodes = skill.path("toolBindings");
                if (bindingNodes.isArray()) {
                    for (JsonNode binding : bindingNodes) {
                        bindings.add(new GovernanceSkillBinding(
                                text(binding, "skillToolBindingId"),
                                text(binding, "modelName"),
                                text(binding, "alias"),
                                text(binding, "toolUri")));
                    }
                }
                Integer byteSize = null;
                if (skill.has("byteSize") && skill.get("byteSize").canConvertToInt()) {
                    byteSize = skill.get("byteSize").asInt();
                }
                List<String> resources = new ArrayList<>();
                skill.path("resources").forEach(resource -> resources.add(resource.required("path").asText()));
                skills.add(new GovernanceSkillView(
                        text(skill, "skillId"),
                        text(skill, "name"),
                        text(skill, "description"),
                        text(skill, "version"),
                        text(skill, "instructions"),
                        text(skill, "instructionsRef"),
                        text(skill, "contentDigest"),
                        byteSize,
                        bindings, resources));
            }
        }
        return new GovernanceSnapshot(
                text(body, "tenantId"),
                text(body, "appId"),
                text(body, "subject"),
                text(body, "snapshotId"),
                tools,
                skills);
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

    private static String encodePath(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private record Exchange(int status, JsonNode body, String transportError, String etag) {
        static Exchange of(int status, JsonNode body) {
            return new Exchange(status, body, null, null);
        }

        static Exchange of(int status, JsonNode body, String etag) {
            return new Exchange(status, body, null, etag);
        }

        static Exchange failed(String error) {
            return new Exchange(0, null, error, null);
        }
    }

    private record TextExchange(int status, String body, String transportError) {
        static TextExchange of(int status, String body) {
            return new TextExchange(status, body, null);
        }

        static TextExchange failed(String error) {
            return new TextExchange(0, null, error);
        }
    }
}
