package com.beyondzy.demo.governance;

import java.util.Map;

/** Snapshot 中的一条工具绑定。inputSchema 是 JSON 对象，不是字符串。 */
public record GovernanceToolView(
        String uri,
        String name,
        String description,
        Map<String, Object> inputSchema,
        String bindingId,
        String cardId,
        String cardVersion,
        String riskLabel) {}
