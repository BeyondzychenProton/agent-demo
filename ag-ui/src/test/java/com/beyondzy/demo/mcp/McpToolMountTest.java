package com.beyondzy.demo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.session.ChatSessions;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpToolMountTest {

    @Test
    void registersSafeNamesAndKeepsCallerPhoneSeparateFromArguments() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string"));
        schema.put("properties", properties);
        List<McpToolDefinition> definitions = List.of(
                new McpToolDefinition("identity.resolve", "查人", schema, false),
                new McpToolDefinition("room.reserve", "预约", Map.of("type", "object"), true));
        AtomicReference<String> seenPhone = new AtomicReference<>();
        AtomicReference<Map<String, Object>> seenArguments = new AtomicReference<>();
        Toolkit toolkit = new Toolkit();
        List<McpBoundTool> tools = McpToolMount.install(toolkit, definitions, (name, arguments, phone) -> {
            seenPhone.set(phone);
            seenArguments.set(new LinkedHashMap<>(arguments));
            return McpCallResult.success(Map.of("resolutionStatus", "RESOLVED"));
        }, userId -> "7".equals(userId) ? "13600000000" : null);
        schema.put("mutated", true);

        assertEquals(List.of("identity_resolve", "room_reserve"),
                tools.stream().map(McpBoundTool::getName).toList());
        assertFalse(tools.get(0).writes());
        assertTrue(tools.get(1).writes());
        assertFalse(tools.get(0).getParameters().containsKey("mutated"));

        ToolResultBlock result = tools.get(0).callAsync(ToolCallParam.builder()
                .input(Map.of("name", "张三", "phone", "13900000000"))
                .runtimeContext(RuntimeContext.builder().userId("7").sessionId("s").build())
                .build()).block();
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("13600000000", seenPhone.get());
        assertEquals("13900000000", seenArguments.get().get("phone"));
        assertTrue(text(result).contains("RESOLVED"));

        ToolResultBlock missing = tools.get(0).callAsync(ToolCallParam.builder()
                .input(Map.of("name", "张三"))
                .runtimeContext(RuntimeContext.builder().userId("local-user").sessionId("s").build())
                .build()).block();
        assertEquals(ToolResultState.ERROR, missing.getState());
        assertTrue(text(missing).contains("UNAUTHENTICATED"));
    }

    @Test
    void occupiedNameGetsMcpSuffix() {
        Toolkit toolkit = new Toolkit();
        McpToolMount.install(toolkit, List.of(
                new McpToolDefinition("identity.resolve", "查人", Map.of("type", "object"), false)),
                (name, arguments, phone) -> McpCallResult.success(Map.of()),
                userId -> "13600000000");
        List<McpBoundTool> again = McpToolMount.install(toolkit, List.of(
                new McpToolDefinition("identity.resolve", "查人", Map.of("type", "object"), false)),
                (name, arguments, phone) -> McpCallResult.success(Map.of()),
                userId -> "13600000000");
        assertEquals("identity_resolve_mcp", again.get(0).getName());
    }

    @Test
    void promptStaysUnchangedUntilMcpToolsAreMounted() {
        String base = ChatSessions.systemPrompt(false);
        assertFalse(base.contains("满天星"));
        assertTrue(ChatSessions.systemPrompt(true).contains("根据当前可用工具的描述和参数"));
        assertFalse(ChatSessions.systemPrompt(true).contains("identity_resolve"));
    }

    private static String text(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().get(0)).getText();
    }

    @Test
    void blankToolNamesAreSkipped() {
        List<McpToolDefinition> definitions = new ArrayList<>();
        definitions.add(new McpToolDefinition(" ", "空", Map.of("type", "object"), false));
        definitions.add(new McpToolDefinition("department.search", null, null, false));
        List<McpBoundTool> tools = McpToolMount.install(
                new Toolkit(), definitions, (name, arguments, phone) -> McpCallResult.success(Map.of()),
                userId -> null);
        assertEquals(List.of("department_search"), tools.stream().map(McpBoundTool::getName).toList());
        assertTrue(tools.get(0).getParameters().containsKey("additionalProperties"));
    }
}
