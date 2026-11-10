package com.beyondzy.demo.config;

import com.beyondzy.demo.governance.GovernanceClient;
import com.beyondzy.demo.session.AgentSessionService;
import com.beyondzy.demo.session.ChatSessions;
import com.beyondzy.demo.session.MySqlSessionLogStore;
import com.beyondzy.demo.display.DisplayIndexService;
import com.beyondzy.demo.readmodel.ReadModelPersistenceService;
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
            @Value("${governance.base-url:}") String governanceBaseUrl,
            @Value("${governance.agent-token:}") String governanceAgentToken)
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
                                + "Demo Agent 将只有本地工具，不会挂载 Governance BoundTool");
            }
        } else {
            logger.info("治理客户端已配置 baseUrl={}，将在 ChatSessions 启动时拉取 Snapshot 并挂载 BoundTool",
                    governanceBaseUrl.trim());
        }
        // 启动恢复使用种子用户命名空间；请求期由 UserContext 覆盖为当前登录用户。
        return new ChatSessions(workspacePath, model,
                mySqlSessionLogStore, "1", displayIndex, readModelPersistence, agentSessionService, governance);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
