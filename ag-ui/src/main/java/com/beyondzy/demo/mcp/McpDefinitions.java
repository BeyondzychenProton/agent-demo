package com.beyondzy.demo.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class McpDefinitions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private McpDefinitions() {}
    public static Map<String,Object> schema(McpToolDefinition d) {
        return d.inputSchema()==null || d.inputSchema().isEmpty()
                ? Map.of("type","object","additionalProperties",true) : d.inputSchema();
    }
    public static String hash(McpToolDefinition d) {
        try {
            Object canonical = sorted(Map.of("name",d.name(),"description",Objects.toString(d.description(),""),
                    "inputSchema",schema(d),"write",d.write()));
            byte[] bytes = JSON.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) { throw new IllegalArgumentException("工具定义无法解析",e); }
    }
    private static Object sorted(Object value) {
        if (value instanceof Map<?,?> m) {
            var result = new TreeMap<String,Object>();
            m.forEach((k,v)->result.put(k.toString(),sorted(v)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(McpDefinitions::sorted).toList();
        return value;
    }
}
