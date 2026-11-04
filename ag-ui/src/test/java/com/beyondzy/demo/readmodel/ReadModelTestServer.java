package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.session.ChatSessions;
import com.beyondzy.demo.web.chat.ChatController;
import com.beyondzy.demo.web.readmodel.ReadModelController;
import com.beyondzy.demo.web.agui.AguiController;
import com.beyondzy.demo.agui.AguiService;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import reactor.core.publisher.Flux;

/** 独立联调服务使用临时工作区和脚本模型，绝不连接用户的真实模型或运行目录。 */
public final class ReadModelTestServer {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({ChatController.class, ReadModelController.class, AguiController.class, AguiService.class})
    static class Config {
        @Bean(destroyMethod = "close") ChatSessions sessions() throws Exception {
            Path workspace = System.getProperty("demo.test.workspace") == null ? Files.createTempDirectory("展示联调-")
                    : Path.of(System.getProperty("demo.test.workspace"));
            var sessions = new ChatSessions(workspace, new ScriptModel());
            seed(sessions, 120, 20);
            sessions.projection().sync("long-history");
            return sessions;
        }
    }

    static class ScriptModel implements Model {
        public String getModelName() { return "本地展示联调模型"; }
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String text = messages.stream().filter(message -> message.getRole() == MsgRole.USER)
                    .reduce((a, b) -> b).map(Msg::getTextContent).orElse("");
            if (text.contains("审批") && messages.stream().noneMatch(message -> message.hasContentBlocks(ToolUseBlock.class)))
                return Flux.just(ChatResponse.builder().id("approval-response").content(List.of(ToolUseBlock.builder()
                        .id("test-write").name("write_note").input(Map.of("path", "notes/联调.md", "content", "联调记录"))
                        .content("{\"path\":\"notes/联调.md\",\"content\":\"联调记录\"}").build())).finishReason("tool_calls").build());
            return Flux.range(0, 5).delayElements(Duration.ofMillis(text.contains("慢速验证") ? 600 : 100)).map(index -> ChatResponse.builder()
                    .id("reply-" + messages.size()).content(List.of(TextBlock.builder().text(index == 4 ? "完成。" : "流式测试片段，").build()))
                    .finishReason(index == 4 ? "stop" : null).build());
        }
    }

    public static ConfigurableApplicationContext start(int port) {
        return new SpringApplication(Config.class).run("--server.port=" + port, "--server.address=127.0.0.1",
                "--spring.main.web-application-type=reactive", "--spring.main.banner-mode=off");
    }

    public static void seed(ChatSessions sessions, int count, int chunks) {
        var log = sessions.log("long-history");
        if (log.head().seq() > 0) return;
        var writer = log.acquire("fixture", Duration.ofMinutes(5));
        try {
            List<SessionEvent> events = new ArrayList<>(); long seq = 0;
            for (int i = 1; i <= count; i++) {
                String id = "history-" + i;
                String text = i == count ? "超长正文🙂\n```java\n代码\n```\n".repeat(60000) : "历史消息 " + i;
                for (int j = 0; j < chunks; j++) events.add(new SessionEvent(1, UUID.randomUUID().toString(), ++seq,
                        System.currentTimeMillis(), "model/chunk", "fixture", "turn-" + i, true,
                        ReadModelStore.json(Map.of("modelCallId", id, "chunk", Map.of("id", id, "content", List.of(Map.of("type", "text", "text", "片段")))))));
                var message = Msg.builder().id(id).role(MsgRole.ASSISTANT).content(TextBlock.builder().text(text).build()).build();
                events.add(new SessionEvent(1, UUID.randomUUID().toString(), ++seq, System.currentTimeMillis(),
                        "message/assistant", "fixture", "turn-" + i, true, ReadModelStore.json(Map.of("message", message))));
                events.add(new SessionEvent(1, UUID.randomUUID().toString(), ++seq, System.currentTimeMillis(),
                        "run/end", "fixture", "turn-" + i, true, "{\"status\":\"completed\"}"));
                // 以多个提交模拟真实日志链，避免只有一个大批次的样本掩盖链遍历成本。
                if (events.size() >= 256) {
                    log.commit(writer, UUID.randomUUID().toString(), log.head().seq(), List.copyOf(events)); events.clear();
                }
            }
            if (!events.isEmpty()) log.commit(writer, UUID.randomUUID().toString(), log.head().seq(), events);
        } finally { log.release(writer); }
    }

    public static void main(String[] args) {
        var context = start(18087);
        System.out.println("展示联调服务已就绪：18087");
    }
}
