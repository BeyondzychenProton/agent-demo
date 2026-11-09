package com.beyondzy.demo.governance;

import java.util.Map;

/**
 * 治理 invoke 的分类结果。传输失败没有完整 InvokeResponse，用 {@link Kind#TRANSPORT} 表示。
 */
public record GovernanceInvokeResult(
        Kind kind,
        String traceId,
        String callId,
        String outcome,
        String code,
        String message,
        boolean agentMayRetry,
        boolean rebindRequired,
        Map<String, Object> result,
        Map<String, Object> extensions) {

    public enum Kind {
        COMPLETED,
        REJECTED,
        FAILED,
        UNCERTAIN,
        TRANSPORT
    }

    public GovernanceInvokeResult {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public static GovernanceInvokeResult completed(String code, String message, Map<String, Object> result) {
        return new GovernanceInvokeResult(
                Kind.COMPLETED, null, null, "completed", code, message, false, false, result, Map.of());
    }

    public static GovernanceInvokeResult rejected(
            String code, String message, boolean agentMayRetry, boolean rebindRequired) {
        return new GovernanceInvokeResult(
                Kind.REJECTED, null, null, "rejected", code, message, agentMayRetry, rebindRequired, null, Map.of());
    }

    public static GovernanceInvokeResult failed(
            String code, String message, boolean agentMayRetry, boolean rebindRequired) {
        return new GovernanceInvokeResult(
                Kind.FAILED, null, null, "failed", code, message, agentMayRetry, rebindRequired, null, Map.of());
    }

    public static GovernanceInvokeResult uncertain(String code, String message) {
        return new GovernanceInvokeResult(
                Kind.UNCERTAIN, null, null, "uncertain", code, message, false, false, null, Map.of());
    }

    public static GovernanceInvokeResult transport(String code, String message) {
        return new GovernanceInvokeResult(
                Kind.TRANSPORT, null, null, null, code, message, false, false, null, Map.of());
    }
}
