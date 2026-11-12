package com.beyondzy.demo.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.beyondzy.demo.config.SkillJdbc;
import com.beyondzy.demo.readmodel.ReadModelPersistenceService;
import com.beyondzy.demo.mcp.McpBoundTool;
import com.beyondzy.demo.mcp.McpCallResult;
import com.beyondzy.demo.mcp.McpToolDefinition;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** 会话装配挂上 JDBC 技能库，并把 get_current_time 注册成只读允许。 */
class ChatSessionsSkillWiringTest {

    @TempDir Path workspace;

    @Test
    void meetingWritesRequireApprovalAndReadsAreAllowed() throws Exception {
        McpBoundTool read = new McpBoundTool((name, args, phone) -> McpCallResult.success(Map.of()),
                id -> "13800000000", "room_search", new McpToolDefinition("room.search", "查询", Map.of("type", "object"), false));
        McpBoundTool write = new McpBoundTool((name, args, phone) -> McpCallResult.success(Map.of()),
                id -> "13800000000", "room_reserve", new McpToolDefinition("room.reserve", "预约", Map.of("type", "object"), true));
        var method = ChatSessions.class.getDeclaredMethod("approvalPermissions", List.class);
        method.setAccessible(true);
        var rules = (PermissionContextState) method.invoke(null, List.of(read, write));
        assertEquals(PermissionBehavior.ALLOW, rules.getAllowRules().get("room_search").get(0).behavior());
        assertEquals(PermissionBehavior.ASK, rules.getAskRules().get("room_reserve").get(0).behavior());
    }

    @Test
    void registersClockToolReadOnlyAndAllow() throws Exception {
        DataSource dataSource = memory();
        try (JdbcAgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, SkillJdbc.dialect(dataSource));
                ChatSessions sessions = open(repo)) {
            HarnessAgent agent = agent(sessions);
            ToolBase clock = assertInstanceOf(ToolBase.class, agent.getToolkit().getTool("get_current_time"));
            assertTrue(clock.isReadOnly());
            assertTrue(clock.isConcurrencySafe());
            assertEquals(PermissionBehavior.ALLOW, initialPermissions(agent)
                    .getAllowRules().get("get_current_time").get(0).behavior());
            assertFalse(agent.getToolkit().getToolNames().contains("skill_manage"));
            assertNull(agent.getToolkit().getTool("lookup_order"));
            assertNull(agent.getToolkit().getTool("search_docs"));
            assertNull(agent.getToolkit().getTool("write_note"));
            assertTrue(agent.getSkillRepositories().stream()
                    .anyMatch(item -> item instanceof JdbcAgentSkillRepository));
        }
    }

    private ChatSessions open(JdbcAgentSkillRepository repo) {
        return new ChatSessions(workspace, new IdleModel(), new EmptyStore(), "local-user",
                null, mock(ReadModelPersistenceService.class), null, null, repo);
    }

    private static HarnessAgent agent(ChatSessions sessions) throws Exception {
        Field field = ChatSessions.class.getDeclaredField("agent");
        field.setAccessible(true);
        return (HarnessAgent) field.get(sessions);
    }

    /** 初始权限在构建时写入，不打开会话日志。 */
    private static PermissionContextState initialPermissions(HarnessAgent agent) throws Exception {
        Field field = ReActAgent.class.getDeclaredField("initialPermissionContext");
        field.setAccessible(true);
        return (PermissionContextState) field.get(agent.getDelegate());
    }

    private static DataSource memory() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wiring-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private static final class EmptyStore implements SessionLogStore {
        @Override
        public SessionLog open(SessionKey key, io.agentscope.core.agent.RuntimeContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionKey> list(io.agentscope.core.agent.RuntimeContext context) {
            return List.of();
        }
    }

    private static final class IdleModel implements Model {
        @Override
        public String getModelName() {
            return "skill-wiring";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }
    }
}
