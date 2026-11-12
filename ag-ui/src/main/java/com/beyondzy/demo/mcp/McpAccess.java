package com.beyondzy.demo.mcp;

/**
 * 启动时的本地 MCP 装配参数。
 *
 * @param discoveryPhone 只用于启动时的 tools/list，不作为后续业务调用的身份
 */
public record McpAccess(
        McpClient client,
        McpPhoneLookup phones,
        String discoveryPhone) {}
