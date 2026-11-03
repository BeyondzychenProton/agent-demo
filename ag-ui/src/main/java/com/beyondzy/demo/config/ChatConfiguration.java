package com.beyondzy.demo.config;

import com.beyondzy.demo.session.ChatSessions;
import com.beyondzy.demo.session.MySqlSessionLogStore;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
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

    @Bean(destroyMethod = "close")
    ChatSessions sessions(
            MySqlSessionLogStore mySqlSessionLogStore,
            DataSource dataSource,
            @Value("${chat.workspace}") String workspace,
            @Value("${chat.user-id:1}") String userId,
            @Value("${chat.base-url}") String baseUrl,
            @Value("${chat.endpoint-path:/chat/completions}") String endpointPath,
            @Value("${chat.model}") String modelName,
            @Value("${chat.api-key}") String key)
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
        return new ChatSessions(workspacePath, model,
                mySqlSessionLogStore, userId, dataSource);
    }
}
