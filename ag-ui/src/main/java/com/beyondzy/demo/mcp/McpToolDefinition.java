package com.beyondzy.demo.mcp;

import java.util.Map;

/** tools/list 里的一条工具。write 为真时必须先经过人工确认。 */
public record McpToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema,
        boolean write) {}
