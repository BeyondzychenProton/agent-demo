package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.service.skill.impl.SkillManagementServiceImpl;
import com.beyondzy.demo.service.skill.support.SkillDraft;
import com.beyondzy.demo.service.skill.view.SkillManagementView;
import com.beyondzy.demo.service.skill.view.SkillVersionView;

import static org.junit.jupiter.api.Assertions.*;

import com.beyondzy.demo.config.skill.VersionedSkillRepository;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.*;
import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证生效指针、固定引用和每轮内容快照，不依赖真实模型或远端会议写入。 */
class SkillReleaseLifecycleTest {
    @Test
    void migrationSnapshotsActualRuntimeEvenWhenUnrelatedDraftAlreadyExists() {
        try (var h = SkillAdminHarness.open()) {
            h.user(1, null, 0, true);
            h.admin.create(1, new SkillDraft("meeting-booking", "运行说明", "ACTUAL_RUNTIME", "0", null, "0",
                    List.of(new SkillDraft.SkillFile("references/room.md", "ACTUAL_RESOURCE"))));
            draft(h, "meeting-booking", "UNPUBLISHED_OTHER_CONTENT");
            // 模拟升级前没有版本号的旧绑定，而不是通过新绑定接口提前生成基线。
            h.jdbc.update("INSERT INTO ai_agent_skill (agent_id, skill_id, created_by, gmt_create, gmt_modified) "
                    + "SELECT 'session-chat', id, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM agentscope_skills");
            assertEquals(1, h.baselines.migrate(1));
            assertEquals(0, h.baselines.migrate(1));
            Long current = h.jdbc.queryForObject("SELECT current_version_id FROM ai_skill_release", Long.class);
            assertEquals("ACTUAL_RUNTIME", h.versions.get(current).content());
            assertEquals(current.toString(), h.admin.agent("session-chat").skills().getFirst().versionId());
            assertEquals("ACTUAL_RESOURCE", repo(h).capture("session-chat", "1").skills().getFirst().getResource("references/room.md"));
            assertEquals(h.repository.getSkill("meeting-booking").getSkillContent(),
                    repo(h).capture("session-chat", "1").skills().getFirst().getSkillContent());
            assertEquals("0", h.admin.detail("meeting-booking").status());
        }
    }

    @Test
    void publishedUpdateDoesNotUpgradeAgentAndGlobalDisableOnlyAffectsNewTurns() {
        try (var h = SkillAdminHarness.open()) {
            h.user(1, null, 0, true);
            h.admin.create(1, new SkillDraft("meeting-booking", "版本 A", "BODY_A", "0", null, "0", List.of()));
            h.admin.replaceBindings(1, "session-chat", List.of("meeting-booking"));
            String originalBody = h.repository.getSkill("meeting-booking").getSkillContent();
            var repository = repo(h);
            var before = RuntimeContext.builder().put(VersionedSkillRepository.Snapshot.class, repository.capture("session-chat", "1")).build();
            String pinned = h.admin.agent("session-chat").skills().getFirst().versionId();
            var draft = draft(h, "meeting-booking", "BODY_B");
            h.versions.publish(1, Long.parseLong(draft.id()), new SkillVersionService.PublishRequest(draft.revision(), "2.0.0", List.of()));
            assertEquals("BODY_B", h.admin.detail("meeting-booking").content().trim());
            assertEquals(pinned, h.admin.agent("session-chat").skills().getFirst().versionId());
            assertEquals(originalBody, repository.capture("session-chat", "1").skills().getFirst().getSkillContent());
            h.admin.replaceVersionBindings(1, "session-chat", List.of(new SkillAdminService.Binding("meeting-booking", draft.id())));
            assertEquals(h.repository.getSkill("meeting-booking").getSkillContent(), repository.capture("session-chat", "1").skills().getFirst().getSkillContent());
            assertEquals(originalBody, repository.getAllSkills(before).getFirst().getSkillContent());
            h.admin.setEnabled("meeting-booking", false);
            assertTrue(repository.capture("session-chat", "1").skills().isEmpty());
            assertEquals(originalBody, repository.getAllSkills(before).getFirst().getSkillContent());
            assertEquals(draft.id(), h.admin.agent("session-chat").skills().getFirst().versionId());
            // 保存其他配置不得因为该 Skill 已停用而误解绑。
            h.admin.replaceVersionBindings(1, "session-chat", List.of(new SkillAdminService.Binding("meeting-booking", draft.id())));
            assertEquals(1, h.bindings());
            assertTrue(repository.getAllSkills(RuntimeContext.empty()).isEmpty());
        }
    }

    @Test
    void catalogFindsUnpublishedDraftsAndAllowsOverlapWithoutDuplicatingSkills() {
        try (var h = SkillAdminHarness.open()) {
            h.user(1, null, 0, true);
            h.jdbc.update("UPDATE app_user SET name = '维护人' WHERE id = 1");
            h.admin.create(1, new SkillDraft("live-skill", "运行", "BODY", "0", null, "0", List.of()));
            h.baselines.migrate(1);
            var liveDraft = draft(h, "live-skill", "DRAFT");
            var unpublished = draft(h, "new-skill", "NEW_DRAFT");
            h.versions.update(1, Long.parseLong(unpublished.id()), new SkillVersionService.UpdateRequest(
                    unpublished.revision(), "说明", "NEW_DRAFT", "0", null, List.of(), List.of(), "中文展示名"));
            var management = management(h);
            var all = management.page(null, "all", "all", 1, 20);
            assertEquals(2, all.total());
            assertEquals(new SkillManagementView.Counts(2, 1, 0, 2), all.counts());
            assertEquals(2, management.page(null, "draft", "all", 1, 20).total());
            assertEquals(1, management.page("中文展示名", "all", "all", 1, 20).total());
            assertEquals(2, management.page("维护人", "all", "all", 1, 20).total());
            assertNull(all.items().stream().filter(item -> item.name().equals("new-skill")).findFirst().orElseThrow().status());
            assertEquals(liveDraft.id(), management.edit(1, "live-skill").id());
            assertEquals("BODY", management.overview("live-skill").current().content());
            assertEquals(400, assertThrows(SkillException.class, () -> h.admin.replaceVersionBindings(1, "session-chat",
                    List.of(new SkillAdminService.Binding("live-skill", liveDraft.id())))).status());
            var latest = h.versions.get(Long.parseLong(unpublished.id()));
            h.versions.publish(1, Long.parseLong(latest.id()), new SkillVersionService.PublishRequest(latest.revision(), "1.0.0", List.of()));
            assertEquals("1", h.admin.detail("new-skill").status());
            assertEquals(latest.id(), management.overview("new-skill").item().currentVersionId());
        }
    }

    private static SkillVersionView draft(SkillAdminHarness h, String name, String body) {
        return h.versions.create(1, new SkillVersionService.CreateRequest(name, null, "说明", body, "0", null, List.of()));
    }
    private static VersionedSkillRepository repo(SkillAdminHarness h) {
        return new VersionedSkillRepository(h.repository, h.mapper(AgentSkillMapper.class), h.mapper(SkillVersionMapper.class),
                h.mapper(SkillVersionFileMapper.class), h.visibility);
    }
    private static SkillManagementService management(SkillAdminHarness h) {
        return new SkillManagementServiceImpl(h.mapper(SkillManagementMapper.class), h.mapper(SkillMapper.class),
                h.baselines, h.versions, h.admin, h.mapper(SkillVersionMapper.class));
    }
}
