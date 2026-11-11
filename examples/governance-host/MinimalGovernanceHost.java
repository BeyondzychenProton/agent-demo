import com.beyondzy.demo.governance.adapter.AgentScopeGovernanceAdapter;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.util.Map;


/**
 * 第二宿主最小入口：只配置治理地址与令牌，一次 {@link AgentScopeGovernanceAdapter#attach}。
 *
 * <p>用法：
 * <pre>
 *   GOVERNANCE_BASE_URL=http://127.0.0.1:8091
 *   GOVERNANCE_AGENT_TOKEN=dev-agent-reader-token
 *   java ... MinimalGovernanceHost
 * </pre>
 */
public final class MinimalGovernanceHost {

    private MinimalGovernanceHost() {}

    public static void main(String[] args) throws Exception {
        String baseUrl = System.getenv("GOVERNANCE_BASE_URL");
        String token = System.getenv("GOVERNANCE_AGENT_TOKEN");
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
            System.err.println("用法: MinimalGovernanceHost [toolName] [jsonArguments]");
            System.err.println("或设置环境变量 GOVERNANCE_BASE_URL / GOVERNANCE_AGENT_TOKEN");
            System.exit(2);
        }
        AgentScopeGovernanceAdapter.AttachedAssembly attached =
                AgentScopeGovernanceAdapter.attach(baseUrl, token);
        System.out.println("snapshotId=" + attached.snapshotId());
        System.out.println("tools=" + attached.toolNames());
        System.out.println("skillsBlank=" + (attached.skillsSection() == null || attached.skillsSection().isBlank()));
        if (args.length != 0) {
            Map<String, Object> input = args.length == 1 ? Map.of()
                    : new com.fasterxml.jackson.databind.ObjectMapper().readValue(args[1],
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            var tool = attached.toolkit().getTool(args[0]);
            if (tool == null) throw new IllegalArgumentException("当前快照没有工具 " + args[0]);
            var result = tool.callAsync(ToolCallParam.builder()
                            .toolUseBlock(ToolUseBlock.builder().id("cli").name(args[0]).input(input)
                                    .content(args.length == 1 ? "{}" : args[1]).build())
                            .input(input)
                            .build())
                    .block(Duration.ofSeconds(30));
            for (var output : result.getOutput()) {
                if (output instanceof TextBlock text) System.out.println(text.getText());
            }
            if (result.getState() == ToolResultState.ERROR) System.exit(1);
        }
    }

}
