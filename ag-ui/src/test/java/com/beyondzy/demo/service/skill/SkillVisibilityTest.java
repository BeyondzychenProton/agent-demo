package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.service.skill.SkillTestSupport.Fixture;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 技能与用户绑定：平台技能所有人可见、用户技能只对本人可见、同名时用户层覆盖平台层。
 *
 * <p>同时验证框架侧入口 {@link UserScopedSkillRepository}：同一个实例按
 * {@code RuntimeContext.userId} 返回不同技能集（不需要每用户一个 Agent）。
 */
class SkillVisibilityTest {

    private static final long ALICE = 1L;
    private static final long BOB = 4L;

    @Test
    void platformSkillVisibleToEveryone() {
        try (Fixture fixture = Fixture.open("visible-platform")) {
            fixture.service.saveMarkdown(markdown("code-reviewer", "平台技能"), false);

            assertEquals(Set.of("code-reviewer"), names(fixture, ALICE));
            assertEquals(Set.of("code-reviewer"), names(fixture, BOB));
        }
    }

    @Test
    void userSkillVisibleOnlyToOwner() {
        try (Fixture fixture = Fixture.open("visible-user")) {
            fixture.service.saveMarkdown(markdown("alice-notes", "alice 私有"), false, ALICE);

            assertEquals(Set.of("alice-notes"), names(fixture, ALICE));
            assertTrue(names(fixture, BOB).isEmpty(), "bob 不应看到 alice 的私有技能");
            // 无身份的调用只按平台视图处理
            assertTrue(fixture.store.listVisible(SkillStore.PLATFORM_OWNER).isEmpty());
        }
    }

    @Test
    void sameNameUserLayerOverridesPlatformLayer() {
        try (Fixture fixture = Fixture.open("visible-overlay")) {
            fixture.service.saveMarkdown(markdown("code-reviewer", "平台版"), false);
            fixture.service.saveMarkdown(markdown("code-reviewer", "alice 版"), false, ALICE);

            List<SkillStore.SkillRecord> aliceVisible = fixture.store.listVisible(ALICE);
            assertEquals(1, aliceVisible.size(), "同名只应返回一份");
            assertEquals("alice 版", aliceVisible.get(0).description());

            List<SkillStore.SkillRecord> bobVisible = fixture.store.listVisible(BOB);
            assertEquals(1, bobVisible.size());
            assertEquals("平台版", bobVisible.get(0).description());

            // 框架侧同样按用户区分
            UserScopedSkillRepository repository = new UserScopedSkillRepository(fixture.store);
            AgentSkill aliceSkill = repository.getSkill("code-reviewer", ctx(ALICE));
            assertEquals("alice 版", aliceSkill.getDescription());
            AgentSkill bobSkill = repository.getSkill("code-reviewer", ctx(BOB));
            assertEquals("平台版", bobSkill.getDescription());
        }
    }

    @Test
    void deleteTargetsExactOwner() {
        try (Fixture fixture = Fixture.open("visible-delete")) {
            fixture.service.saveMarkdown(markdown("code-reviewer", "平台版"), false);
            fixture.service.saveMarkdown(markdown("code-reviewer", "alice 版"), false, ALICE);

            fixture.service.delete("code-reviewer", ALICE);
            assertEquals("平台版", fixture.store.listVisible(ALICE).get(0).description());

            fixture.service.delete("code-reviewer");
            assertTrue(fixture.store.listVisible(ALICE).isEmpty());
            assertNull(fixture.store.findVisible(ALICE, "code-reviewer"));
        }
    }

    @Test
    void repositoryRejectsWrites() {
        try (Fixture fixture = Fixture.open("visible-readonly")) {
            UserScopedSkillRepository repository = new UserScopedSkillRepository(fixture.store);
            assertThrows(UnsupportedOperationException.class,
                    () -> repository.save(List.of(), false));
            assertThrows(UnsupportedOperationException.class, () -> repository.delete("x"));
        }
    }

    @Test
    void invalidOwnerIsRejected() {
        try (Fixture fixture = Fixture.open("visible-owner-invalid")) {
            assertThrows(SkillException.class,
                    () -> fixture.service.saveMarkdown(markdown("x", "y"), false, -1L));
        }
    }

    // ------------------------------------------------------------------

    private static Set<String> names(Fixture fixture, long userId) {
        return fixture.store.listVisible(userId).stream()
                .map(SkillStore.SkillRecord::name)
                .collect(Collectors.toSet());
    }

    private static RuntimeContext ctx(long userId) {
        return RuntimeContext.builder().userId(String.valueOf(userId)).build();
    }

    private static byte[] markdown(String name, String description) {
        return ("""
                ---
                name: %s
                description: %s
                ---
                # %s
                """).formatted(name, description, name).getBytes(StandardCharsets.UTF_8);
    }
}
