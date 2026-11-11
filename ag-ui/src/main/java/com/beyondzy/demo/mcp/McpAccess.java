package com.beyondzy.demo.mcp;

import java.nio.file.Path;

/**
 * 启动时的本地 MCP 装配参数。
 *
 * @param discoveryPhone 只用于启动时的 tools/list，不作为后续业务调用的身份
 * @param skillsDir 外部技能目录；不存在时改用内置 meeting-booking
 */
public record McpAccess(
        McpClient client,
        McpPhoneLookup phones,
        String discoveryPhone,
        Path skillsDir) {}
