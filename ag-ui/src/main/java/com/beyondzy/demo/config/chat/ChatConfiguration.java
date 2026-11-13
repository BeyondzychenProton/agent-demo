package com.beyondzy.demo.config.chat;

import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.mapper.skill.SkillResourceMapper;
import com.beyondzy.demo.mcp.McpServer;
import com.beyondzy.demo.mcp.mantianxing.MantianxingMcpClient;
import com.beyondzy.demo.service.display.DisplayIndexService;
import com.beyondzy.demo.service.readmodel.ReadModelPersistenceService;
import com.beyondzy.demo.service.session.AgentSessionService;
import com.beyondzy.demo.service.skill.SkillStore;
import com.beyondzy.demo.service.skill.UserScopedSkillRepository;
import com.beyondzy.demo.service.user.UserService;
import com.beyondzy.demo.session.ChatSessions;
import com.beyondzy.demo.session.MySqlSessionLogStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运行时装配：模型客户端 + 会话管理器 + 技能仓库 + 用户工作空间。
 *
 * <p>两个关键变化：
 * <ul>
 *   <li>工作区根目录改为 {@code chat.workspace-root}（默认 {@code ./data/workspace}），
 *       框架按 {@code IsolationScope.USER} 落到 {@code <root>/<userId>/}；</li>
 *   <li>技能仓库改成自管的 {@link UserScopedSkillRepository}：平台技能 + 本人技能，
 *       同名时本人优先。原生的 {@code JdbcAgentSkillRepository} 依赖 namespace 列，
 *       与本库历史表结构不兼容，已不再使用。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ChatConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(ChatConfiguration.class);

    /** 技能持久化：owner_user_id 表达归属（0 = 平台公共）。 */
    @Bean
    public SkillStore skillStore(SkillMapper skills, SkillResourceMapper resources) {
        // 单独 new：Spring Boot 4 不再自动暴露 Jackson 的 ObjectMapper bean
        return new SkillStore(skills, resources, new ObjectMapper());
    }

    /** 框架侧技能仓库：每次调用按 RuntimeContext 取"平台 + 本人"。 */
    @Bean
    public UserScopedSkillRepository skillRepository(SkillStore store) {
        return new UserScopedSkillRepository(store);
    }

    @Bean(destroyMethod = "close")
    ChatSessions sessions(
            MySqlSessionLogStore mySqlSessionLogStore,
            DisplayIndexService displayIndex,
            ReadModelPersistenceService readModelPersistence,
            AgentSessionService agentSessionService,
            @Value("${chat.base-url}") String baseUrl,
            @Value("${chat.endpoint-path:/chat/completions}") String endpointPath,
            @Value("${chat.model}") String modelName,
            @Value("${chat.api-key}") String key,
            @Value("${chat.workspace-root:./data/workspace}") String workspaceRoot,
            UserService users,
            @Value("${mcp.endpoint:}") String mcpEndpoint,
            @Value("${mcp.app-key:}") String mcpAppKey,
            @Value("${mcp.secret:}") String mcpSecret,
            @Value("${mcp.discovery-phone:}") String mcpDiscoveryPhone,
            UserScopedSkillRepository skillRepository)
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
        List<McpServer> mcpServers = new ArrayList<>();
        MantianxingMcpClient mantianxing = MantianxingMcpClient.ifConfigured(
                mcpEndpoint, mcpAppKey, mcpSecret, mcpDiscoveryPhone, userId -> phoneOf(users, userId));
        if (mantianxing == null) {
            if (hasText(mcpEndpoint) || hasText(mcpAppKey) || hasText(mcpSecret)) {
                logger.warn("满天星 MCP 未启用：需要同时配置 mcp.endpoint、mcp.app-key 与 mcp.secret");
            }
        } else {
            logger.info("满天星 MCP 已配置 endpoint={}", mcpEndpoint.trim());
            mcpServers.add(mantianxing);
        }
        Path workspace = Path.of(workspaceRoot).toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        logger.info("用户工作空间根目录: {}", workspace);
        // 启动恢复使用种子用户命名空间；请求期由 UserContext 覆盖为当前登录用户。
        return new ChatSessions(workspace, model,
                mySqlSessionLogStore, "1", displayIndex, readModelPersistence, agentSessionService, mcpServers,
                skillRepository, users);
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
