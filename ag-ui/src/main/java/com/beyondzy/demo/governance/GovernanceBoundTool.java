package com.beyondzy.demo.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 一个 Snapshot 工具的执行器。snapshotId、uri 和绑定信息在注册时固定；
 * 模型生成的 arguments 原样交给治理底座，不能覆盖外层绑定或请求身份。
 */
public final class GovernanceBoundTool extends ToolBase {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceBoundTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String BEHAVIOR_HINT =
            "多名候选人先向用户确认；未找到要如实说明；调用被拒绝、绑定失效或失败时说明未成功，不要编造。";

    private final GovernanceGateway gateway;
    private final boolean requireUserCredential;
    private final String snapshotId;
    private final String uri;
    private final String bindingId;
    private final String cardId;
    private final String cardVersion;

    public GovernanceBoundTool(
            GovernanceGateway gateway,
            String registeredName,
            GovernanceSnapshot snapshot,
            GovernanceToolView tool) {
        this(gateway, registeredName, snapshot, tool, true);
    }

    GovernanceBoundTool(GovernanceGateway gateway, String registeredName, GovernanceSnapshot snapshot,
            GovernanceToolView tool, boolean requireUserCredential) {
        super(ToolBase.builder()
                .name(registeredName)
                .description(describe(tool))
                .inputSchema(copySchema(tool.inputSchema()))
                .readOnly(read(tool.riskLabel()))
                .concurrencySafe(true));
        this.gateway = gateway;
        this.requireUserCredential = requireUserCredential;
        this.snapshotId = snapshot.snapshotId();
        this.uri = tool.uri();
        this.bindingId = tool.bindingId();
        this.cardId = tool.cardId();
        this.cardVersion = tool.cardVersion();
    }

    public String snapshotId() {
        return snapshotId;
    }

    public String uri() {
        return uri;
    }

    public String bindingId() {
        return bindingId;
    }

    public String cardId() {
        return cardId;
    }

    public String cardVersion() {
        return cardVersion;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (isReadOnly()) return Mono.just(PermissionDecision.allow(getName()));
        return Mono.just(PermissionDecision.passthrough(getName()));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 工具执行前：仅当 Run 已绑定，或存在 Harness 确认的 command→run 关联时才移交；禁止猜测 pending。
        RuntimeContext runtime = param == null ? null : param.getRuntimeContext();
        RunCredentialRegistry.ensureRunBinding(runtime);
        String rawToken = RunCredentialRegistry.resolve(runtime).orElse(null);
        if (requireUserCredential && (rawToken == null || rawToken.isBlank())) {
            logger.warn("治理 invoke 缺少 Run 作用域用户凭证，工具 {}", getName());
            return Mono.just(GovernanceResults.toToolResult(
                    GovernanceInvokeResult.transport("UNAUTHENTICATED", "缺少出站用户会话凭证")));
        }
        try {
            GovernanceInvokeResult result = requireUserCredential
                    ? OutboundUserCredential.call(rawToken, () -> gateway.invoke(uri, snapshotId, param.getInput()))
                    : gateway.invoke(uri, snapshotId, param.getInput());
            return Mono.just(GovernanceResults.toToolResult(result));
        } catch (RuntimeException error) {
            logger.warn("治理 invoke 失败，工具 {}，异常 {}", getName(), error.getClass().getSimpleName());
            return Mono.just(GovernanceResults.toToolResult(
                    GovernanceInvokeResult.transport("TRANSPORT_FAILURE", "治理调用失败")));
        }
    }

    private static boolean read(String riskLabel) {
        return riskLabel != null && "read".equalsIgnoreCase(riskLabel);
    }

    private static String describe(GovernanceToolView tool) {
        String source = tool.description();
        if (source == null || source.isBlank()) source = tool.name();
        return source + " " + BEHAVIOR_HINT;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copySchema(Map<String, Object> schema) {
        return JSON.convertValue(JSON.valueToTree(schema), Map.class);
    }
}
