package com.beyondzy.demo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "test-secret";

    @Test
    void listsToolsAndCallsWithTheAuthenticatedPhone() throws Exception {
        AtomicInteger lists = new AtomicInteger();
        HttpServer server = server(exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode body = JSON.readTree(raw);
            String method = body.path("method").asText();
            String phone = exchange.getRequestHeaders().getFirst("X-MCP-User-Phone");
            String timestamp = exchange.getRequestHeaders().getFirst("X-MCP-Timestamp");
            assertEquals(McpSignature.sign(SECRET, timestamp, phone),
                    exchange.getRequestHeaders().getFirst("X-MCP-Signature"));
            assertEquals("agent", exchange.getRequestHeaders().getFirst("X-MCP-App-Key"));
            if (!"initialize".equals(method)) {
                assertEquals("sess-1", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            }
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            byte[] response;
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "sess-1");
                response = rpc(body.get("id").asInt(),
                        "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"serverInfo\":{\"name\":\"cms\",\"version\":\"1\"}}")
                        .getBytes(StandardCharsets.UTF_8);
            } else if ("tools/list".equals(method)) {
                int page = lists.getAndIncrement();
                String cursor = body.path("params").path("cursor").asText("");
                assertEquals(page == 0 ? "" : "next", cursor);
                String tools = page == 0
                        ? "{\"tools\":[{\"name\":\"identity.resolve\",\"description\":\"查人\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}}],"
                                + "\"nextCursor\":\"next\"}"
                        : "{\"tools\":[{\"name\":\"room.reserve\",\"description\":\"预约\","
                                + "\"inputSchema\":{\"type\":\"object\"}}]}";
                response = rpc(body.get("id").asInt(), tools).getBytes(StandardCharsets.UTF_8);
            } else if ("tools/call".equals(method)) {
                assertEquals("13700000000", phone);
                assertEquals("13900000000", body.path("params").path("arguments").path("phones").get(0).asText());
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                String event = "event: message\n"
                        + "data: " + rpc(body.get("id").asInt(),
                        "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"status\\\":\\\"CREATED\\\",\\\"meetingId\\\":9}\"}],\"isError\":false}")
                        + "\n\n";
                response = event.getBytes(StandardCharsets.UTF_8);
            } else {
                throw new IllegalStateException(method);
            }
            if (!"tools/call".equals(method)) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        try {
            McpClient client = new McpClient(url(server), "agent", SECRET, Duration.ofSeconds(2));
            List<McpToolDefinition> tools = client.listTools("13600000000");
            assertEquals(2, tools.size());
            assertEquals("identity.resolve", tools.get(0).name());
            assertFalse(tools.get(0).write());
            assertEquals("room.reserve", tools.get(1).name());
            assertTrue(tools.get(1).write());

            McpCallResult result = client.callTool(
                    "room.reserve",
                    Map.of("phones", List.of("13900000000"), "requestId", "reserve-001"),
                    "13700000000");
            assertTrue(result.success());
            assertEquals("CREATED", ((Map<?, ?>) result.body()).get("status"));
            assertEquals(9, ((Map<?, ?>) result.body()).get("meetingId"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authFailureIsNotASuccessfulToolResult() throws Exception {
        HttpServer server = server(exchange -> {
            byte[] body = "<html>访问受限</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        try {
            McpClient client = new McpClient(url(server), "agent", SECRET, Duration.ofSeconds(2));
            McpCallResult result = client.callTool("identity.resolve", Map.of("name", "张三"), "13600000000");
            assertFalse(result.success());
            assertEquals("UNAUTHENTICATED", result.code());
            assertNull(result.body());
            assertThrows(McpCallException.class, () -> client.listTools("13600000000"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingConfigurationDoesNotCreateAClient() {
        assertTrue(McpClient.ifConfigured("", "agent", SECRET) == null);
        assertTrue(McpClient.ifConfigured("http://127.0.0.1/mcp", " ", SECRET) == null);
        assertTrue(McpClient.ifConfigured("http://127.0.0.1/mcp", "agent", " ") == null);
    }

    @Test
    void unknownToolsAskUnlessMarkedReadOnly() {
        assertTrue(McpClient.writeOperation("calendar.create", null));
        assertFalse(McpClient.writeOperation("calendar.create", true));
        assertFalse(McpClient.writeOperation("meeting.list_mine", false));
        assertTrue(McpClient.writeOperation("meeting.cancel", true));
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stage-api/mcp", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/stage-api/mcp";
    }

    private static String rpc(int id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
    }
}
