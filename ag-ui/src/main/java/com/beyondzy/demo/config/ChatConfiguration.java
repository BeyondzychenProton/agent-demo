package com.beyondzy.demo.config;

import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.governance.GovernanceClient;
import com.beyondzy.demo.mcp.McpAccess;
import com.beyondzy.demo.mcp.McpClient;
import com.beyondzy.demo.session.AgentSessionService;
import com.beyondzy.demo.session.ChatSessions;
import com.beyondzy.demo.session.MySqlSessionLogStore;
import com.beyondzy.demo.display.DisplayIndexService;
import com.beyondzy.demo.readmodel.ReadModelPersistenceService;
import com.beyondzy.demo.user.UserService;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运行时装配：模型客户端 + 会话管理器。
 *
 * <p>SessionLog 由 MySQL 会话存储实现持久化；工作区目录仍用于本地工具文件。
 * 模型走 DashScope 的 OpenAI 兼容端点，由 baseUrl + endpointPath 拼成 /chat/completions。
 */
@Configuration(proxyBeanMethods = false)
public class ChatConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(ChatConfiguration.class);

    @Bean(destroyMethod = "close")
    ChatSessions sessions(
            MySqlSessionLogStore mySqlSessionLogStore,
            DisplayIndexService displayIndex,
            ReadModelPersistenceService readModelPersistence,
            AgentSessionService agentSessionService,
            @Value("${chat.workspace}") String workspace,
            @Value("${chat.base-url}") String baseUrl,
            @Value("${chat.endpoint-path:/chat/completions}") String endpointPath,
            @Value("${chat.model}") String modelName,
            @Value("${chat.api-key}") String key,
            UserService users,
            @Value("${governance.base-url:}") String governanceBaseUrl,
            @Value("${governance.agent-token:}") String governanceAgentToken,
            @Value("${mcp.endpoint:}") String mcpEndpoint,
            @Value("${mcp.app-key:}") String mcpAppKey,
            @Value("${mcp.secret:}") String mcpSecret,
            @Value("${mcp.discovery-phone:}") String mcpDiscoveryPhone,
            @Value("${mcp.skills-dir:}") String mcpSkillsDir)
            throws IOException {
        // 真实模型必须提供 API key，缺失直接报错，不静默降级
        if (key.isBlank()) {
            throw new IllegalArgumentException("未配置 chat.api-key，无法连接模型");
        }
        Model model =
                OpenAIChatModel.builder()
                        .apiKey(key)
                        .baseUrl(baseUrl)
                        .endpointPath(endpointPath)
                        .modelName(modelName)
                        .stream(true) // 开启流式，产生 committed 的 model/chunk 事件供增量重放
                        .build();
        // 创建工作区目录（本地工具文件根目录）
        Path workspacePath = Files.createDirectories(Path.of(workspace));
        GovernanceClient governance = GovernanceClient.ifConfigured(governanceBaseUrl, governanceAgentToken);
        if (governance == null) {
            if (hasText(governanceBaseUrl) != hasText(governanceAgentToken)) {
                logger.warn("治理接入未启用：需要同时配置 governance.base-url 与 governance.agent-token");
            } else if (!hasText(governanceBaseUrl) && !hasText(governanceAgentToken)) {
                logger.warn(
                        "治理接入未启用：GOVERNANCE_BASE_URL / GOVERNANCE_AGENT_TOKEN 均为空；"
                                + "不会挂载 Governance BoundTool");
            }
        } else {
            logger.info("治理客户端已配置 baseUrl={}，将在 ChatSessions 启动时拉取 Snapshot 并挂载 BoundTool",
                    governanceBaseUrl.trim());
        }
        McpClient mcpClient = McpClient.ifConfigured(mcpEndpoint, mcpAppKey, mcpSecret);
        McpAccess mcp = null;
        if (mcpClient == null) {
            if (hasText(mcpEndpoint) || hasText(mcpAppKey) || hasText(mcpSecret)) {
                logger.warn("本地 MCP 未启用：需要同时配置 mcp.endpoint、mcp.app-key 与 mcp.secret");
            }
        } else {
            logger.info("本地 MCP 已配置 endpoint={}", mcpEndpoint.trim());
            mcp = new McpAccess(
                    mcpClient,
                    userId -> phoneOf(users, userId),
                    mcpDiscoveryPhone,
                    hasText(mcpSkillsDir) ? Path.of(mcpSkillsDir.trim()) : null);
        }
        // 启动恢复使用种子用户命名空间；请求期由 UserContext 覆盖为当前登录用户。
        return new ChatSessions(workspacePath, model,
                mySqlSessionLogStore, "1", displayIndex, readModelPersistence, agentSessionService, governance, mcp);
    }

    private static String phoneOf(UserService users, String userId) {
        if (userId == null || userId.isBlank() || !userId.chars().allMatch(Character::isDigit)) return null;
        UserBean user = users.findById(Long.parseLong(userId));
        if (user == null || user.getPhone() == null || user.getPhone().isBlank()) return null;
        return user.getPhone().strip();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
