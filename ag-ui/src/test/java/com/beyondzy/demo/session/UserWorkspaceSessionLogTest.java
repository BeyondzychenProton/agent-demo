package com.beyondzy.demo.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.beyondzy.demo.service.readmodel.ReadModelPersistenceService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** 会话日志必须落在对应用户的工作区，重启后仍能按同一身份读回。 */
class UserWorkspaceSessionLogTest {
    @TempDir Path workspace;

    @Test
    void journalLivesUnderTheUserWorkspaceAndSurvivesReopen() throws Exception {
        SessionEvent event = new SessionEvent(1, "e", 1, 1, "run/start", "r", "t", true, "{}");
        try (ChatSessions first = open("7")) {
            var log = first.log("conv");
            var writer = log.acquire("owner", Duration.ofMinutes(1));
            try {
                log.commit(writer, "batch", 0, List.of(event));
            } finally {
                log.release(writer);
            }
        }

        Path userRuntime = workspace.resolve("7").resolve(".agentscope-runtime");
        assertTrue(Files.isDirectory(userRuntime), "日志应写在用户目录下的 .agentscope-runtime");
        assertFalse(Files.exists(workspace.resolve(".agentscope-runtime")),
                "日志不应写到工作区根目录");

        try (ChatSessions second = open("7")) {
            assertEquals(List.of("conv"), second.list());
            assertEquals(List.of(event), second.log("conv").readAfter(0, 10));
        }
        try (ChatSessions other = open("8")) {
            assertTrue(other.list().isEmpty());
        }
    }

    private ChatSessions open(String userId) {
        return new ChatSessions(workspace, new IdleModel(), null, userId,
                null, mock(ReadModelPersistenceService.class));
    }

    private static final class IdleModel implements Model {
        @Override
        public String getModelName() {
            return "workspace-log";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }
    }
}
