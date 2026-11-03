package com.beyondzy.demo.support;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨投影模块共用的内容/JSON 读取工具。
 *
 * <p>M3（live 帧）和 M4（历史页面模型）都需要从 ContentBlock / ToolResultBlock 里取文本、
 * 解析 JSON、归一卡片状态；放在 support 里避免 M3 反向依赖 M4。
 */
public final class ContentValues {

    private ContentValues() {}

    public static String textOf(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock value && value.getText() != null) {
                text.append(value.getText());
            }
        }
        return text.toString();
    }

    public static String resultText(ToolResultBlock result) {
        return result == null ? null : textOf(result.getOutput());
    }

    public static Object parseJson(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return text;
        try {
            Object parsed = JsonUtils.getJsonCodec().fromJson(trimmed, Object.class);
            // 工具返回 String 时，框架可能把它整体序列化成 JSON 字符串字面量，这里再解一层。
            if (parsed instanceof String inner) {
                String nested = inner.strip();
                if (nested.startsWith("{") || nested.startsWith("[")) {
                    try {
                        return JsonUtils.getJsonCodec().fromJson(nested, Object.class);
                    } catch (RuntimeException ignored) {
                        return inner;
                    }
                }
            }
            return parsed;
        } catch (RuntimeException ignored) {
            return text;
        }
    }

    public static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    public static String cardStatus(String raw) {
        if (raw == null) return "requested";
        return switch (raw) {
            case "success" -> "completed";
            case "error", "denied" -> "failed";
            default -> raw;
        };
    }
}
