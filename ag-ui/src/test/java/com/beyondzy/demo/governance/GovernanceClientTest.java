package com.beyondzy.demo.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GovernanceClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void snapshotAndInvokeKeepBusinessArgumentsAndBoundIdentity() throws Exception {
        List<Recorded> seen = new CopyOnWriteArrayList<>();
        AtomicInteger invokes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/snapshot/current", exchange -> {
            seen.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Agent-Token"), ""));
            byte[] body = snapshotJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/agent/invoke", exchange -> {
            invokes.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Agent-Token"), request));
            byte[] body = """
                    {"traceId":"tr_1","callId":"call_1","outcome":"failed","code":"UPSTREAM_NOT_READY",
                     "message":"上游未就绪","agentMayRetry":true,"rebindRequired":false,"result":null,"extensions":{}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            GovernanceClient client = new GovernanceClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "dev-agent-invoke-demo-token",
                    Duration.ofSeconds(2));
            GovernanceSnapshot snapshot = client.currentSnapshot();
            assertEquals("snap_live", snapshot.snapshotId());
            assertEquals("tenant-a", snapshot.tenantId());
            assertEquals("identity.resolve", snapshot.tools().get(0).name());
            assertEquals(20, snapshot.tools().get(0).inputSchema().get("properties") instanceof Map<?, ?> properties
                    ? ((Map<?, ?>) ((Map<?, ?>) properties).get("name")).get("maxLength")
                    : null);

            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("city", "杭州");
            nested.put("tags", List.of("a", "b"));
            nested.put("note", null);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("name", "田沐阳");
            arguments.put("phone", "17600000000");
            arguments.put("mobile", "13900000000");
            arguments.put("uri", "driver://schema-uri-field");
            arguments.put("subject", "biz-subject");
            arguments.put("snapshotId", "snap_from_model");
            arguments.put("token", "model-token");
            arguments.put("tenantId", "other-tenant");
            arguments.put("profile", nested);
            arguments.put("aliases", List.of("甲", "乙"));
            arguments.put("optional", null);

            GovernanceInvokeResult result = client.invoke(
                    "driver://mcp/tenant-a/cms-identity-invoke/identity.resolve",
                    "snap_live",
                    arguments);
            assertEquals(GovernanceInvokeResult.Kind.FAILED, result.kind());
            assertEquals("UPSTREAM_NOT_READY", result.code());
            assertTrue(result.agentMayRetry());
            assertFalse(result.rebindRequired());
            assertEquals(1, invokes.get());

            assertEquals("17600000000", arguments.get("phone"));
            assertEquals("snap_from_model", arguments.get("snapshotId"));
            assertSame(nested, arguments.get("profile"));
            assertNull(((Map<?, ?>) arguments.get("profile")).get("note"));

            Recorded post = seen.stream().filter(item -> "POST".equals(item.method())).findFirst().orElseThrow();
            assertEquals("dev-agent-invoke-demo-token", post.token());
            JsonNode body = JSON.readTree(post.body());
            assertEquals("driver://mcp/tenant-a/cms-identity-invoke/identity.resolve", body.get("uri").asText());
            assertEquals("snap_live", body.get("snapshotId").asText());
            JsonNode args = body.get("arguments");
            assertEquals("田沐阳", args.get("name").asText());
            assertEquals("17600000000", args.get("phone").asText());
            assertEquals("13900000000", args.get("mobile").asText());
            assertEquals("driver://schema-uri-field", args.get("uri").asText());
            assertEquals("biz-subject", args.get("subject").asText());
            assertEquals("snap_from_model", args.get("snapshotId").asText());
            assertEquals("model-token", args.get("token").asText());
            assertEquals("other-tenant", args.get("tenantId").asText());
            assertEquals("杭州", args.get("profile").get("city").asText());
            assertEquals(2, args.get("profile").get("tags").size());
            assertTrue(args.get("profile").get("note").isNull());
            assertEquals(2, args.get("aliases").size());
            assertTrue(args.get("optional").isNull());
            assertFalse(body.has("tenantId"));
            assertFalse(body.has("bindingId"));
            assertFalse(body.has("token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesContractOutcomesAndFilterUnauthorizedResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger mode = new AtomicInteger();
        server.createContext("/api/agent/invoke", exchange -> {
            int current = mode.get();
            byte[] body;
            int status;
            if (current == 0) {
                status = 200;
                body = """
                        {"traceId":"tr","callId":"c","outcome":"completed","code":"OK","message":"调用成功",
                         "agentMayRetry":false,"rebindRequired":false,
                         "result":{"content":[{"type":"text","text":"{\\"resolutionStatus\\":\\"RESOLVED\\"}"}],"isError":false},
                         "extensions":{}}
                        """.getBytes(StandardCharsets.UTF_8);
            } else if (current == 1) {
                status = 403;
                body = """
                        {"traceId":"tr","callId":"c","outcome":"rejected","code":"AUTHZ_DENIED",
                         "message":"当前授权不允许调用该工具","agentMayRetry":false,"rebindRequired":false,
                         "result":null,"extensions":{}}
                        """.getBytes(StandardCharsets.UTF_8);
            } else if (current == 2) {
                status = 504;
                body = """
                        {"traceId":"tr","callId":"c","outcome":"uncertain","code":"EXECUTION_UNCERTAIN",
                         "message":"结果不确定","agentMayRetry":false,"rebindRequired":false,"result":null,"extensions":{}}
                        """.getBytes(StandardCharsets.UTF_8);
            } else {
                status = 401;
                body = "{\"message\":\"无效的 Agent 凭证\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            GovernanceClient client = new GovernanceClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                    "token-a",
                    Duration.ofSeconds(2));
            GovernanceInvokeResult ok = client.invoke("driver://mcp/tenant-a/demo/identity.resolve", "snap", Map.of("name", "田沐阳"));
            assertEquals(GovernanceInvokeResult.Kind.COMPLETED, ok.kind());
            assertEquals("OK", ok.code());
            assertFalse(Boolean.TRUE.equals(ok.result().get("isError")));

            mode.set(1);
            GovernanceInvokeResult denied = client.invoke("driver://mcp/tenant-a/demo/identity.resolve", "snap", Map.of());
            assertEquals(GovernanceInvokeResult.Kind.REJECTED, denied.kind());
            assertEquals("AUTHZ_DENIED", denied.code());
            assertNull(denied.result());

            mode.set(2);
            GovernanceInvokeResult uncertain = client.invoke("driver://mcp/tenant-a/demo/identity.resolve", "snap", Map.of());
            assertEquals(GovernanceInvokeResult.Kind.UNCERTAIN, uncertain.kind());
            assertEquals("EXECUTION_UNCERTAIN", uncertain.code());

            mode.set(3);
            GovernanceInvokeResult unauthorized = client.invoke("driver://mcp/tenant-a/demo/identity.resolve", "snap", Map.of());
            assertEquals(GovernanceInvokeResult.Kind.TRANSPORT, unauthorized.kind());
            assertEquals("UNAUTHENTICATED", unauthorized.code());
            assertTrue(unauthorized.message().contains("无效的 Agent 凭证"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transportFailureIsNotASuccessfulResult() {
        GovernanceClient client = new GovernanceClient(
                "http://127.0.0.1:9", "token-a", Duration.ofMillis(300));
        GovernanceInvokeResult result = client.invoke(
                "driver://mcp/tenant-a/demo/identity.resolve", "snap", Map.of("name", "田沐阳"));
        assertEquals(GovernanceInvokeResult.Kind.TRANSPORT, result.kind());
        assertEquals("TRANSPORT_FAILURE", result.code());
        assertFalse(result.agentMayRetry());
    }

    @Test
    void missingConfigurationDoesNotCreateAClient() {
        assertTrue(GovernanceClient.ifConfigured("", "token") == null);
        assertTrue(GovernanceClient.ifConfigured("http://127.0.0.1:8091", " ") == null);
        assertTrue(GovernanceClient.ifConfigured("http://127.0.0.1:8091", "token-a") != null);
    }

    private static String snapshotJson() {
        return """
                {"tenantId":"tenant-a","appId":"default","subject":"agent-invoke-demo",
                 "snapshotId":"snap_live","etag":"ab","createdAt":"2026-11-08T12:00:00Z",
                 "policyVersion":"cd","skills":[],
                 "tools":[{"uri":"driver://mcp/tenant-a/cms-identity-invoke/identity.resolve",
                   "name":"identity.resolve","description":"只读身份查询",
                   "inputSchema":{"type":"object","properties":{"name":{"type":"string","maxLength":20}},
                     "required":["name"],"additionalProperties":false},
                   "schemaDigest":"digest","contractVersion":1,
                   "bindingId":"9:3:identity.resolve@1","cardId":9,"cardVersion":3,"riskLabel":"read"}]}
                """;
    }

    private record Recorded(String method, String path, String token, String body) {}
}
