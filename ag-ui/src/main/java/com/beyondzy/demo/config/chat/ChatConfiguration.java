package com.beyondzy.demo.config.chat;

import com.beyondzy.demo.config.langfuse.LangfuseTracing;
import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.config.skill.VersionedSkillRepository;
import com.beyondzy.demo.mapper.skill.AgentSkillMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionFileMapper;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.mcp.McpServer;
import com.beyondzy.demo.mcp.mantianxing.MantianxingMcpClient;
import com.beyondzy.demo.service.mcp.*;
import java.time.Duration;
import com.beyondzy.demo.service.session.AgentSessionService;
import com.beyondzy.demo.session.ChatSessions;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import javax.sql.DataSource;
import com.beyondzy.demo.service.display.DisplayIndexService;
import com.beyondzy.demo.service.readmodel.ReadModelPersistenceService;
import com.beyondzy.demo.service.skill.SkillVisibilityService;
import com.beyondzy.demo.service.user.UserService;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运行时装配：模型客户端 + 会话管理器。
 *
 * <p>SessionLog 使用官方 {@code WorkspaceSessionLogStore}，按 {@code IsolationScope.USER}
 * 写到 {@code chat.workspace/<userId>/.agentscope-runtime/}。
 * 模型走 DashScope 的 OpenAI 兼容端点，由 baseUrl + endpointPath 拼成 /chat/completions。
 */
@Configuration(proxyBeanMethods = false)
public class ChatConfiguration {

    @Bean
    AbstractJdbcDialect skillDialect(DataSource dataSource) {
        return SkillJdbc.dialect(dataSource);
    }

    /** 只读。管理端用自己的 SQL 写技能，这里的 save 不会改行。 */
    @Bean(destroyMethod = "close")
    AgentSkillRepository skillRepository(DataSource dataSource, AbstractJdbcDialect skillDialect,
            AgentSkillMapper bindings, SkillVersionMapper versions, SkillVersionFileMapper files,
            SkillVisibilityService visibility) {
        return new VersionedSkillRepository(new JdbcAgentSkillRepository(dataSource, skillDialect, false),
                bindings, versions, files, visibility);
    }

    @Bean(destroyMethod = "close")
    ChatSessions sessions(
            DisplayIndexService displayIndex,
            ReadModelPersistenceService readModelPersistence,
            AgentSessionService agentSessionService,
            @Value("${chat.base-url}") String baseUrl,
            @Value("${chat.endpoint-path:/chat/completions}") String endpointPath,
            @Value("${chat.model}") String modelName,
            @Value("${chat.api-key}") String key,
            @Value("${chat.workspace}") String workspace,
            UserService users,
            ObjectProvider<McpServer> mcpServers,
            AgentSkillRepository skillRepository,
            SkillVisibilityService skillVisibility,
            LangfuseTracing langfuseTracing) {
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
        // sessionLogStore 传 null：由 ChatSessions 按官方 USER 工作区创建日志后端。
        // 启动时会扫描已有用户目录并恢复调度；请求期 userId 仍由 UserContext 覆盖。
        return new ChatSessions(Path.of(workspace), model,
                null, "1", displayIndex, readModelPersistence, agentSessionService, mcpServers.orderedStream().toList(),
                skillRepository, users, langfuseTracing.middlewares(), skillVisibility);
    }

    /** 同一个实例同时给聊天和技能目录用，避免目录再造一套发现结果。 */
    @Bean
    McpServer mantianxingMcp(
            UserService users,
            McpRegistry registry,
            McpManagementService management,
            McpInvocationGuard guard,
            @Value("${mcp.environment:未配置}") String environment,
            @Value("${mcp.endpoint:}") String mcpEndpoint,
            @Value("${mcp.app-key:}") String mcpAppKey,
            @Value("${mcp.secret:}") String mcpSecret,
            @Value("${mcp.discovery-phone:}") String mcpDiscoveryPhone) {
        if (!hasText(mcpEndpoint) || !hasText(mcpAppKey) || !hasText(mcpSecret)) return null;
        MantianxingMcpClient client = new MantianxingMcpClient(mcpEndpoint, mcpAppKey, mcpSecret,
                Duration.ofSeconds(15), mcpDiscoveryPhone, userId -> phoneOf(users,userId),guard);
        registry.register(client,new McpRegistry.Descriptor("满天星 OA · 会议服务",mcpEndpoint,
                "Streamable HTTP","应用签名 · 凭证已配置",environment));
        return new ManagedMcpServer(client,management,registry);
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
