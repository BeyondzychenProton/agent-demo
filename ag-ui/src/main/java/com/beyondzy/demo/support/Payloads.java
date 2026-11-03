package com.beyondzy.demo.support;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话事件 payload 的解码辅助。
 *
 * <p>SessionLog 里的 payload 是 JSON；测试或部分历史数据可能缺少 ContentBlock 的 {@code type}
 * 判别字段，这里统一补上，避免因为一个展示字段导致整条快照不可读。
 */
public final class Payloads {

    private Payloads() {}

    public static Msg message(Object value) {
        if (value instanceof Msg message) return message;
        return JsonUtils.getJsonCodec().convertValue(value, Msg.class);
    }

    public static ToolUseBlock toolUse(Object value) {
        if (value instanceof ToolUseBlock call) return call;
        return JsonUtils.getJsonCodec()
                .convertValue(withType(value, "tool_use"), ToolUseBlock.class);
    }

    public static ToolResultBlock toolResult(Object value) {
        if (value instanceof ToolResultBlock result) return result;
        return JsonUtils.getJsonCodec()
                .convertValue(withType(value, "tool_result"), ToolResultBlock.class);
    }

    private static Object withType(Object value, String type) {
        if (value instanceof Map<?, ?> map && !map.containsKey("type")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            copy.put("type", type);
            return copy;
        }
        return value;
    }
}
