package com.beyondzy.demo.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 一个本地 MCP 工具。登录手机号在执行时按 RuntimeContext 的用户 id 查询，
 * 模型 arguments 里的手机号只作为业务参数原样转发。
 */
public final class McpBoundTool extends ToolBase {

    private static final Logger logger = LoggerFactory.getLogger(McpBoundTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HINT =
            "登录身份只来自当前账号。多名候选人先确认；即使只有一人也要请用户确认。"
                    + "预约和取消必须先得到明确同意。失败不要编造成功，也不要换 requestId 自动重试。";

    private final McpToolCaller caller;
    private final McpPhoneLookup phones;
    private final String sourceName;
    private final boolean write;

    public McpBoundTool(
            McpToolCaller caller,
            McpPhoneLookup phones,
            String registeredName,
            McpToolDefinition definition) {
        super(ToolBase.builder()
                .name(registeredName)
                .description(describe(definition))
                .inputSchema(copySchema(definition.inputSchema()))
                .readOnly(!definition.write())
                .concurrencySafe(!definition.write()));
        if (caller == null) throw new IllegalArgumentException("MCP 调用器不能为空");
        if (phones == null) throw new IllegalArgumentException("手机号查询不能为空");
        this.caller = caller;
        this.phones = phones;
        this.sourceName = definition.name();
        this.write = definition.write();
    }

    public String sourceName() {
        return sourceName;
    }

    public boolean writes() {
        return write;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (!write) return Mono.just(PermissionDecision.allow(getName()));
        return Mono.just(PermissionDecision.passthrough(getName()));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext runtime = param == null ? null : param.getRuntimeContext();
        String userId = runtime == null ? null : runtime.getUserId();
        String phone;
        try {
            phone = userId == null || userId.isBlank() ? null : phones.phoneOf(userId);
        } catch (RuntimeException error) {
            logger.warn("查询登录用户手机号失败，工具 {}", getName());
            return Mono.just(errorResult("UNAUTHENTICATED", "无法确认当前登录用户手机号"));
        }
        if (phone == null || phone.isBlank()) {
            return Mono.just(errorResult("UNAUTHENTICATED", "当前登录用户没有可用手机号"));
        }
        Map<String, Object> arguments = param == null || param.getInput() == null
                ? Map.of()
                : param.getInput();
        McpCallResult result = caller.callTool(sourceName, arguments, phone.strip());
        return Mono.just(toResult(result));
    }

    private ToolResultBlock toResult(McpCallResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", sourceName);
        if (result.success()) {
            payload.put("result", result.body());
            payload.put("notice", notice(true));
            return ToolResultBlock.text(write(payload)).withState(ToolResultState.SUCCESS);
        }
        payload.put("code", result.code());
        payload.put("message", result.message());
        payload.put("result", result.body());
        payload.put("notice", notice(false));
        return ToolResultBlock.error(write(payload));
    }

    private ToolResultBlock errorResult(String code, String message) {
        return toResult(McpCallResult.failure(code, message, null));
    }

    private String notice(boolean success) {
        if (!success) return "调用未成功。不要编造人员、时间或会议室，不要自动换 requestId 重试。";
        if (write) return "这是写入结果。同一笔操作的重试必须沿用原来的 requestId 和参数。";
        if ("identity.resolve".equals(sourceName)) return "请用户确认身份后再继续。不要在回复里扩散完整手机号。";
        return "这是查询结果。不要编造没有返回的人员、空闲时间或会议室。";
    }

    private static String describe(McpToolDefinition definition) {
        String source = definition.description();
        if (source == null || source.isBlank()) source = definition.name();
        return source + " " + HINT;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copySchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            Map<String, Object> open = new LinkedHashMap<>();
            open.put("type", "object");
            open.put("additionalProperties", true);
            return open;
        }
        return JSON.convertValue(JSON.valueToTree(schema), Map.class);
    }

    private static String write(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            return "{\"code\":\"TRANSPORT_FAILURE\",\"notice\":\"调用未成功。结果无法序列化。\"}";
        }
    }
}
