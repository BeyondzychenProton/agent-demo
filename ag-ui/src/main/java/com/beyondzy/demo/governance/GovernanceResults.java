package com.beyondzy.demo.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把治理结果交给模型。业务结果保持原样；拒绝、失败和不确定结果不写成成功。 */
public final class GovernanceResults {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GovernanceResults() {}

    public static ToolResultBlock toToolResult(GovernanceInvokeResult result) {
        boolean toolError = result.kind() == GovernanceInvokeResult.Kind.COMPLETED
                && ("TOOL_EXECUTION_ERROR".equals(result.code()) || isError(result.result()));
        Map<String, Object> payload = new LinkedHashMap<>();
        if (result.outcome() != null) payload.put("outcome", result.outcome());
        if (result.code() != null) payload.put("code", result.code());
        if (result.message() != null) payload.put("message", result.message());
        payload.put("agentMayRetry", result.agentMayRetry());
        payload.put("rebindRequired", result.rebindRequired());
        if (result.kind() == GovernanceInvokeResult.Kind.COMPLETED) payload.put("result", result.result());
        if (result.extensions() != null && !result.extensions().isEmpty()) {
            payload.put("extensions", result.extensions());
        }
        payload.put("notice", notice(result, toolError));
        String text = write(payload);
        if (result.kind() == GovernanceInvokeResult.Kind.COMPLETED && !toolError) {
            return ToolResultBlock.text(text).withState(ToolResultState.SUCCESS);
        }
        return ToolResultBlock.error(text);
    }

    private static String notice(GovernanceInvokeResult result, boolean toolError) {
        return switch (result.kind()) {
            case COMPLETED -> toolError
                    ? "工具执行返回了错误，请根据 result 说明，不要编造成功结果。"
                    : "这是治理工具的业务结果。多名候选人时先向用户确认；未找到时如实说明。";
            case REJECTED -> result.rebindRequired()
                    ? "调用未成功，需要重新装配 Snapshot 后才能再调用。不要编造人员或业务数据，不要自动重试。"
                    : "调用未成功。不要编造人员或业务数据，不要自动重试。";
            case FAILED -> "调用未成功，本轮未自动重试。不要编造人员或业务数据。";
            case UNCERTAIN -> "调用结果不确定，不要原样重试，也不要编造结果。";
            case TRANSPORT -> "调用未成功。治理服务没有返回可确认的结果，不要编造人员或业务数据。";
        };
    }

    private static boolean isError(Map<String, Object> result) {
        return result != null && Boolean.TRUE.equals(result.get("isError"));
    }

    private static String write(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            return "{\"code\":\"TRANSPORT_FAILURE\",\"notice\":\"调用未成功。结果无法序列化。\"}";
        }
    }
}
