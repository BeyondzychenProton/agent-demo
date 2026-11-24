package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.config.skill.VersionedSkillRepository;
import com.beyondzy.demo.mapper.skill.AgentSkillMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionMapper;
import com.beyondzy.demo.mapper.skill.SkillVersionFileMapper;
import com.beyondzy.demo.contract.UserContext;
import com.beyondzy.demo.service.readmodel.ReadModelPersistenceService;
import com.beyondzy.demo.session.ChatSessions;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.Msg;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 同一智能体、两个部门的用户。过滤器只放技能名。
 * 名单外的 skill-id 加载不到，系统提示和上下文里也没有技能全文。
 */
class SkillTurnFilterTest {

    @TempDir Path workspace;

    @Test
    void twoUsersSeeOnlyTheIntersection() throws Exception {
        try (SkillAdminHarness harness = people();
                ChatSessions sessions = sessions(harness)) {
            assertEquals(List.of("dept-skill", "owner-skill", "public-skill"), names(harness, "1"));
            assertEquals(List.of("public-skill"), names(harness, "2"));
            assertEquals(List.of("public-skill"), names(harness, "3"));
            assertEquals(List.of(), names(harness, "abc"));
            assertEquals(List.of(), names(harness, "0"));
            assertEquals(List.of(), names(harness, "4"));
            assertEquals(List.of(), names(harness, "99"));

            RuntimeContext alice = context(sessions, "1");
            SkillFilter filter = alice.get(SkillFilter.class);
            assertTrue(filter.isAllowed("public-skill"));
            assertTrue(filter.isAllowed("dept-skill"));
            assertFalse(filter.isAllowed("unbound-public"));
            assertFalse(filter.isAllowed("disabled-public"));
            assertFalse(filter.isAllowed("public-skill_" + SkillJdbc.SOURCE));
            assertFalse(alice.getExtra().toString().contains("PUBLIC_BODY"));

            HarnessAgent agent = agent(sessions);
            HarnessSkillMiddleware middleware = agent.getDelegate().getMiddlewares().stream()
                    .filter(HarnessSkillMiddleware.class::isInstance)
                    .map(HarnessSkillMiddleware.class::cast)
                    .findFirst()
                    .orElseThrow();
            String prompt = middleware.onSystemPrompt(agent.getDelegate(), alice, "系统提示").block();
            assertTrue(prompt.contains("public-skill"));
            assertFalse(prompt.contains("PUBLIC_BODY"));
            assertFalse(prompt.contains("DEPT_BODY"));
            assertFalse(prompt.contains("UNBOUND_BODY"));

            String allowed = text(middleware.runtime().loadTool().callAsync(ToolCallParam.builder()
                    .runtimeContext(alice)
                    .input(Map.of("skillId", "public-skill_" + SkillJdbc.SOURCE, "path", "SKILL.md"))
                    .build()).block());
            assertTrue(allowed.contains("PUBLIC_BODY"));

            String missing = text(middleware.runtime().loadTool().callAsync(ToolCallParam.builder()
                    .runtimeContext(alice)
                    .input(Map.of("skillId", "unbound-public_" + SkillJdbc.SOURCE, "path", "SKILL.md"))
                    .build()).block());
            assertTrue(missing.contains("Skill not found"));
            assertFalse(missing.contains("UNBOUND_BODY"));

            RuntimeContext bob = context(sessions, "2");
            assertTrue(bob.get(SkillFilter.class).isAllowed("public-skill"));
            assertFalse(bob.get(SkillFilter.class).isAllowed("dept-skill"));
            assertFalse(bob.get(SkillFilter.class).isAllowed("owner-skill"));
        }
    }

    private ChatSessions sessions(SkillAdminHarness harness) {
        return new ChatSessions(workspace, new IdleModel(), new EmptyStore(), "1",
                null, mock(ReadModelPersistenceService.class), null, null,
                new VersionedSkillRepository(harness.repository, harness.mapper(AgentSkillMapper.class),
                        harness.mapper(SkillVersionMapper.class), harness.mapper(SkillVersionFileMapper.class), harness.visibility),
                null, null, harness.visibility);
    }

    private static List<String> names(SkillAdminHarness harness, String userId) {
        return harness.visibility.namesFor(SkillJdbc.SESSION_AGENT, userId);
    }

    private static RuntimeContext context(ChatSessions sessions, String userId) throws Exception {
        Method method = ChatSessions.class.getDeclaredMethod("context", String.class);
        method.setAccessible(true);
        return UserContext.call(userId, () -> {
            try {
                return (RuntimeContext) method.invoke(sessions, "thread-1");
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    private static HarnessAgent agent(ChatSessions sessions) throws Exception {
        Field field = ChatSessions.class.getDeclaredField("agent");
        field.setAccessible(true);
        return (HarnessAgent) field.get(sessions);
    }

    private static String text(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) return "";
        StringBuilder out = new StringBuilder();
        result.getOutput().forEach(block -> {
            if (block instanceof TextBlock text) out.append(text.getText());
        });
        return out.toString();
    }

    private static SkillAdminHarness people() {
        SkillAdminHarness harness = SkillAdminHarness.open();
        harness.department(10, null, "甲");
        harness.department(11, 10L, "甲的下级");
        harness.department(20, null, "乙");
        harness.user(1, 101L, 10, true);
        harness.user(2, 102L, 20, false);
        harness.user(3, 103L, 11, false);
        harness.jdbc.update(
                "INSERT INTO app_user (id, is_deleted, is_admin, mantianxing_user_id) VALUES (4, 1, 0, NULL)");
        create(harness, "public-skill", "0", null, "PUBLIC_BODY");
        create(harness, "dept-skill", "1", 10L, "DEPT_BODY");
        create(harness, "owner-skill", "2", null, "OWNER_BODY");
        create(harness, "unbound-public", "0", null, "UNBOUND_BODY");
        create(harness, "disabled-public", "0", null, "DISABLED_BODY");
        harness.admin.replaceBindings(1, SkillJdbc.SESSION_AGENT, List.of(
                "public-skill", "dept-skill", "owner-skill", "disabled-public"));
        harness.admin.setEnabled("disabled-public", false);
        return harness;
    }

    private static void create(SkillAdminHarness harness, String name, String scope, Long departmentId, String body) {
        harness.admin.create(1, new SkillDraft(name, name + " 说明", body, scope, departmentId, "0", List.of()));
    }

    private static final class EmptyStore implements SessionLogStore {
        @Override
        public SessionLog open(SessionKey key, RuntimeContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionKey> list(RuntimeContext context) {
            return List.of();
        }
    }

    private static final class IdleModel implements Model {
        @Override
        public String getModelName() {
            return "skill-filter";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }
    }
}
