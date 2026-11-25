package com.beyondzy.demo.mcp.mantianxing;

import com.beyondzy.demo.enums.mcp.McpCallCode;
import com.beyondzy.demo.enums.mcp.McpProvider;
import com.beyondzy.demo.exception.mcp.McpCallException;
import com.beyondzy.demo.mcp.McpCallResult;
import com.beyondzy.demo.mcp.McpServer;
import com.beyondzy.demo.mcp.McpToolDefinition;
import com.beyondzy.demo.mcp.McpInvocationCheck;

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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 满天星 MCP Streamable HTTP 客户端。
 *
 * <p>每次业务调用都用该登录用户的手机号重新 initialize，再 tools/call。
 * 启动时的 tools/list 使用单独的发现手机号，不把这次会话复用给其他用户。
 * 不自动重试 tools/call。
 */
public final class MantianxingMcpClient implements McpServer {

    private static final Logger logger = LoggerFactory.getLogger(MantianxingMcpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String PROTOCOL = "2025-03-26";
    private static final Set<String> KNOWN_READS = Set.of(
            "identity.resolve",
            "department.search",
            "calendar.get_freebusy",
            "calendar.find_slots",
            "calendar.verify",
            "room.search",
            "meeting.list_mine");
    private static final Set<String> KNOWN_WRITES = Set.of("room.reserve", "meeting.cancel");

    private final URI endpoint;
    private final String appKey;
    private final String secret;
    private final HttpClient http;
    private final Duration timeout;
    private final String discoveryPhone;
    private final MantianxingPhoneLookup phones;
    private final AtomicInteger ids = new AtomicInteger();
    private final McpInvocationCheck invocationCheck;
    private final ThreadLocal<Long> discoveryDeadline = new ThreadLocal<>();

    public MantianxingMcpClient(
            String endpoint,
            String appKey,
            String secret,
            Duration timeout,
            String discoveryPhone,
            MantianxingPhoneLookup phones) {
        this(endpoint, appKey, secret, timeout, discoveryPhone, phones, (provider, tool) -> {});
    }

    public MantianxingMcpClient(
            String endpoint, String appKey, String secret, Duration timeout,
            String discoveryPhone, MantianxingPhoneLookup phones, McpInvocationCheck invocationCheck) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("MCP 地址不能为空");
        if (appKey == null || appKey.isBlank()) throw new IllegalArgumentException("MCP App Key 不能为空");
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("MCP Secret 不能为空");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("超时必须大于 0");
        }
        if (phones == null) throw new IllegalArgumentException("手机号查询不能为空");
        String trimmed = endpoint.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        URI uri = URI.create(trimmed);
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException("MCP 地址必须是 http 或 https");
        }
        this.endpoint = uri;
        this.appKey = appKey.trim();
        this.secret = secret;
        this.timeout = timeout;
        this.discoveryPhone = discoveryPhone;
        this.phones = phones;
        this.invocationCheck = java.util.Objects.requireNonNull(invocationCheck);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 地址、App Key、Secret 缺一则不启用。手机号查询只在启用时必需。 */
    public static MantianxingMcpClient ifConfigured(
            String endpoint,
            String appKey,
            String secret,
            String discoveryPhone,
            MantianxingPhoneLookup phones) {
        if (endpoint == null || endpoint.isBlank() || appKey == null || appKey.isBlank()
                || secret == null || secret.isBlank()) {
            return null;
        }
        return new MantianxingMcpClient(endpoint, appKey, secret, Duration.ofSeconds(15), discoveryPhone, phones);
    }

    @Override
    public String id() {
        return McpProvider.MANTIANXING.code();
    }

    @Override
    public List<McpToolDefinition> listTools() {
        return listToolsFor(discoveryPhone);
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments, String userId) {
        String phone;
        try {
            phone = userId == null || userId.isBlank() ? null : phones.phoneOf(userId);
        } catch (RuntimeException error) {
            logger.warn("查询登录用户手机号失败");
            return McpCallResult.failure(McpCallCode.UNAUTHENTICATED, "无法确认当前登录用户手机号", null);
        }
        if (phone == null || phone.isBlank()) {
            return McpCallResult.failure(McpCallCode.UNAUTHENTICATED, "当前登录用户没有可用手机号", null);
        }
        return callWithPhone(toolName, arguments, phone);
    }

    private List<McpToolDefinition> listToolsFor(String userPhone) {
        discoveryDeadline.set(System.nanoTime() + Duration.ofSeconds(45).toNanos());
        try {
        String session = openSession(userPhone);
        JsonNode result = request(userPhone, session, "tools/list", Map.of());
        List<McpToolDefinition> tools = new ArrayList<>();
        String cursor = text(result, "nextCursor");
        collect(result, tools);
        int guard = 0;
        while (cursor != null && guard++ < 20) {
            JsonNode page = request(userPhone, session, "tools/list", Map.of("cursor", cursor));
            collect(page, tools);
            cursor = text(page, "nextCursor");
        }
        if (cursor != null) throw new McpCallException(McpCallCode.DISCOVERY_INCOMPLETE, "工具分页未完整读取");
        return List.copyOf(tools);
        } finally { discoveryDeadline.remove(); }
    }

    private McpCallResult callWithPhone(String toolName, Map<String, Object> arguments, String userPhone) {
        if (toolName == null || toolName.isBlank()) {
            return McpCallResult.failure(McpCallCode.INVALID_ARGUMENT, "工具名不能为空", null);
        }
        try {
            String session = openSession(userPhone);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments));
            JsonNode result = request(userPhone, session, "tools/call", params);
            return interpret(result);
        } catch (McpCallException error) {
            logger.warn("MCP 工具 {} 调用失败 code={}", toolName, error.code());
            return McpCallResult.failure(error.code(), error.getMessage(), null);
        }
    }

    static boolean writeOperation(String name, Boolean readOnlyHint) {
        if (name != null && (KNOWN_WRITES.contains(name) || name.endsWith(".reserve") || name.endsWith(".cancel"))) {
            return true;
        }
        if (name != null && KNOWN_READS.contains(name)) return false;
        // 未知工具默认需要审批，不把服务端提示当成授权策略。
        return true;
    }

    private String openSession(String userPhone) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "ag-ui", "version", "1.0.0"));
        RpcMessage initialized = exchange(userPhone, null, "initialize", params, true);
        exchange(userPhone, initialized.sessionId(), "notifications/initialized", null, false);
        return initialized.sessionId();
    }

    private JsonNode request(String userPhone, String sessionId, String method, Map<String, Object> params) {
        return exchange(userPhone, sessionId, method, params, true).result();
    }

    private RpcMessage exchange(
            String userPhone, String sessionId, String method, Map<String, Object> params, boolean expectResult) {
        String phone = requirePhone(userPhone);
        int id = ids.incrementAndGet();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        if (expectResult) payload.put("id", id);
        payload.put("method", method);
        if (params != null) payload.put("params", params);
        String timestamp = Long.toString(System.currentTimeMillis());
        String signature = MantianxingSignature.sign(secret, timestamp, phone);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout())
                    .header("X-MCP-App-Key", appKey)
                    .header("X-MCP-Timestamp", timestamp)
                    .header("X-MCP-User-Phone", phone)
                    .header("X-MCP-Signature", signature)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
            if (sessionId != null && !sessionId.isBlank()) request.header("Mcp-Session-Id", sessionId);
            // 最终检查必须晚于 initialize，也覆盖用户批准后恢复执行的调用。
            if ("tools/call".equals(method)) invocationCheck.check(id(), String.valueOf(params.get("name")));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String nextSession = response.headers().firstValue("Mcp-Session-Id").orElse(sessionId);
            int status = response.statusCode();
            String raw = response.body();
            if (status == 401) throw new McpCallException(McpCallCode.UNAUTHENTICATED, "MCP 鉴权失败");
            if (status == 503) throw new McpCallException(McpCallCode.SERVER_UNAVAILABLE, "MCP 服务未就绪");
            if (status < 200 || status >= 300) {
                throw new McpCallException(McpCallCode.http(status), httpMessage(status, raw));
            }
            if (!expectResult) return new RpcMessage(nextSession, null);
            JsonNode rpc = selectRpc(raw, id);
            if (rpc == null) throw new McpCallException(McpCallCode.PROTOCOL, "MCP 响应无法解析");
            if (rpc.hasNonNull("error")) {
                String message = rpc.path("error").path("message").asText("MCP 调用失败");
                throw new McpCallException(McpCallCode.PROTOCOL, message);
            }
            if (!rpc.has("result")) throw new McpCallException(McpCallCode.PROTOCOL, "MCP 响应缺少 result");
            return new RpcMessage(nextSession, rpc.get("result"));
        } catch (McpCallException error) {
            throw error;
        } catch (IOException error) {
            throw new McpCallException(McpCallCode.TRANSPORT_FAILURE, "MCP 服务不可达");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpCallException(McpCallCode.TRANSPORT_FAILURE, "MCP 调用被中断");
        }
    }

    private Duration requestTimeout() {
        Long deadline = discoveryDeadline.get();
        if (deadline == null) return timeout;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new McpCallException(McpCallCode.DISCOVERY_TIMEOUT, "工具发现超过总时限");
        return Duration.ofNanos(Math.min(timeout.toNanos(), remaining));
    }

    private static void collect(JsonNode result, List<McpToolDefinition> tools) {
        JsonNode listed = result == null ? null : result.get("tools");
        if (listed == null || !listed.isArray())
            throw new McpCallException(McpCallCode.INVALID_CATALOG, "工具目录缺少有效 tools 数组");
        for (JsonNode tool : listed) {
            String name = text(tool, "name");
            if (name == null || name.length() > 255)
                throw new McpCallException(McpCallCode.INVALID_CATALOG, "工具目录包含无效名称");
            JsonNode schema = tool.get("inputSchema");
            if (schema != null && !schema.isNull() && !schema.isObject())
                throw new McpCallException(McpCallCode.INVALID_CATALOG, "工具输入结构无效");
            Map<String, Object> inputSchema = schema != null && schema.isObject()
                    ? JSON.convertValue(schema, MAP)
                    : null;
            Boolean readOnlyHint = null;
            JsonNode annotations = tool.get("annotations");
            if (annotations != null && annotations.has("readOnlyHint") && !annotations.get("readOnlyHint").isNull()) {
                readOnlyHint = annotations.get("readOnlyHint").asBoolean();
            }
            tools.add(new McpToolDefinition(
                    name,
                    text(tool, "description"),
                    inputSchema,
                    writeOperation(name, readOnlyHint)));
            if (tools.size() > 1000) throw new McpCallException(McpCallCode.INVALID_CATALOG, "工具目录超过限制");
        }
    }

    private static McpCallResult interpret(JsonNode result) {
        if (result == null || result.isNull()) {
            return McpCallResult.failure(McpCallCode.PROTOCOL, "MCP 工具结果为空", null);
        }
        boolean isError = result.path("isError").asBoolean(false);
        Object body = businessBody(result);
        if (isError) return McpCallResult.failure(McpCallCode.TOOL_ERROR, "工具返回错误", body);
        return McpCallResult.success(body);
    }

    private static Object businessBody(JsonNode result) {
        JsonNode structured = result.get("structuredContent");
        if (structured != null && !structured.isNull()) return JSON.convertValue(structured, Object.class);
        StringBuilder text = new StringBuilder();
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode part : content) {
                if (!"text".equals(part.path("type").asText()) || !part.hasNonNull("text")) continue;
                if (text.length() > 0) text.append('\n');
                text.append(part.get("text").asText());
            }
        }
        if (text.length() > 0) return parseMaybeJson(text.toString());
        return JSON.convertValue(result, Object.class);
    }

    private static Object parseMaybeJson(String text) {
        String trimmed = text.strip();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return text;
        try {
            return JSON.readValue(trimmed, Object.class);
        } catch (IOException error) {
            return text;
        }
    }

    private static JsonNode selectRpc(String raw, int id) throws IOException {
        List<JsonNode> messages = messages(raw);
        JsonNode fallback = null;
        for (JsonNode message : messages) {
            if (!message.isObject()) continue;
            JsonNode messageId = message.get("id");
            if (messageId != null && !messageId.isNull() && messageId.asInt() == id) return message;
            if (fallback == null && (message.has("result") || message.has("error"))) fallback = message;
        }
        return fallback;
    }

    private static List<JsonNode> messages(String raw) throws IOException {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = raw.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            JsonNode node = JSON.readTree(trimmed);
            if (node.isArray()) {
                List<JsonNode> listed = new ArrayList<>();
                node.forEach(listed::add);
                return listed;
            }
            return List.of(node);
        }
        List<JsonNode> found = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) {
                flushSse(data, found);
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (data.length() > 0) data.append('\n');
                data.append(value);
            }
        }
        flushSse(data, found);
        return found;
    }

    private static void flushSse(StringBuilder data, List<JsonNode> found) throws IOException {
        if (data.isEmpty()) return;
        String payload = data.toString().strip();
        data.setLength(0);
        if (payload.isEmpty() || "[DONE]".equals(payload)) return;
        if (payload.startsWith("{") || payload.startsWith("[")) found.add(JSON.readTree(payload));
    }

    private static String httpMessage(int status, String raw) {
        if (raw == null || raw.isBlank() || raw.stripLeading().startsWith("<")) return "MCP 返回 HTTP " + status;
        String trimmed = raw.strip().replaceAll("\\s+", " ");
        if (trimmed.length() > 180) trimmed = trimmed.substring(0, 180);
        return "MCP 返回 HTTP " + status + " " + trimmed;
    }

    private static String requirePhone(String userPhone) {
        if (userPhone == null || userPhone.isBlank()) throw new McpCallException(McpCallCode.UNAUTHENTICATED, "缺少登录用户手机号");
        return userPhone.strip();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private record RpcMessage(String sessionId, JsonNode result) {}
}
