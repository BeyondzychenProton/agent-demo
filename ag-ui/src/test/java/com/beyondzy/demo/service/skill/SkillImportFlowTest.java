package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.exception.skill.SkillException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 导入生成草稿，发布才改运行技能。同一次确认和同一平台版本重试不能产生第二份结果。 */
class SkillImportFlowTest {
    @Test
    void uploadKeepsRuntimeEmptyUntilPublish() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(1, null, 0, true);
            byte[] body = markdown("meeting-booking", "预约会议", "正文", null);
            SkillImportView.Created created = harness.imports.upload(1, "key-1", "meeting.md", body);
            assertTrue(created.created());
            assertEquals("UPLOADED", created.view().state());
            assertEquals("REVIEW", created.view().step());
            assertEquals(0, harness.admin.list(null, "all", 1, 20).total());

            SkillImportView.Created again = harness.imports.upload(1, "key-1", "meeting.md", body);
            assertFalse(again.created());
            assertEquals(created.view().id(), again.view().id());
            SkillException conflict = assertThrows(SkillException.class,
                    () -> harness.imports.upload(1, "key-1", "other.md", body));
            assertEquals(409, conflict.status());
            assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
        }
    }

    @Test
    void sameNameNeedsAckAndDoesNotOverwrite() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(1, null, 0, true);
            harness.admin.create(1, new SkillDraft("meeting-booking", "旧说明", "旧正文", "2", null, "0", List.of()));
            SkillImportView uploaded = harness.imports.upload(1, "key-2", "meeting.md",
                    markdown("meeting-booking", "新说明", "新正文", """
                            dependencies:
                              - source: get_current_time
                                capability: clock.read
                                required: true
                                gate: publish
                            """)).view();
            SkillImportView checked = harness.imports.configure(
                    1, Long.parseLong(uploaded.id()), uploaded.revision(), "meeting-booking",
                    "KEEP_ORIGINAL", "PRESERVE_PACKAGE", "0", null);
            assertTrue(checked.targetExists());
            assertEquals("CHECKED", checked.state());
            assertEquals("SUGGESTED", checked.dependencies().get(0).mappingState());
            assertEquals("PLATFORM", checked.dependencies().get(0).targetKind());
            assertFalse(checked.canConfirm());

            SkillImportView mapped = harness.imports.saveDependencies(1, Long.parseLong(checked.id()), checked.revision(),
                    List.of(new SkillImportService.DependencyInput(
                            "get_current_time", "PLATFORM", null, "get_current_time", "CONFIRMED", null)));
            assertTrue(mapped.canConfirm());
            SkillException unacked = assertThrows(SkillException.class, () -> harness.imports.confirm(
                    1, Long.parseLong(mapped.id()), mapped.revision(), false, List.of("TARGET_EXISTS")));
            assertEquals(422, unacked.status());

            SkillImportView confirmed = harness.imports.confirm(
                    1, Long.parseLong(mapped.id()), mapped.revision(), true, List.of("TARGET_EXISTS"));
            assertEquals("CONFIRMED", confirmed.state());
            assertEquals("DRAFT", confirmed.result().versionState());
            assertNull(confirmed.result().platformVersion());
            SkillImportView retried = harness.imports.confirm(
                    1, Long.parseLong(mapped.id()), "0", true, List.of());
            assertEquals(confirmed.result().versionId(), retried.result().versionId());
            assertEquals(1, harness.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_skill_version WHERE import_id = " + mapped.id(), Integer.class));
            assertEquals("旧正文", harness.admin.detail("meeting-booking").content());
            assertEquals(1L, harness.jdbc.queryForObject(
                    "SELECT create_by FROM agentscope_skills WHERE name = 'meeting-booking'", Long.class));

            String versionId = confirmed.result().versionId();
            SkillVersionView draft = harness.versions.get(Long.parseLong(versionId));
            SkillVersionView.Validation blocked = harness.versions.validate(Long.parseLong(versionId), draft.revision());
            assertTrue(blocked.canPublish());
            SkillVersionView.Published published = harness.versions.publish(1, Long.parseLong(versionId),
                    new SkillVersionService.PublishRequest(draft.revision(), "1.0.0", List.of()));
            assertEquals("PUBLISHED", published.state());
            assertEquals("0", published.runtimeStatus());
            assertEquals("新正文", harness.admin.detail("meeting-booking").content().trim());
            assertEquals(1L, harness.jdbc.queryForObject(
                    "SELECT create_by FROM agentscope_skills WHERE name = 'meeting-booking'", Long.class));
            assertEquals(SkillJdbc.SOURCE, harness.repository.getSkill("meeting-booking").getSource());
            SkillVersionView.Published repeated = harness.versions.publish(1, Long.parseLong(versionId),
                    new SkillVersionService.PublishRequest("0", "1.0.0", List.of()));
            assertEquals("1.0.0", repeated.platformVersion());
            assertEquals("新正文", harness.admin.detail("meeting-booking").content().trim());
            assertEquals(409, assertThrows(SkillException.class, () -> harness.versions.publish(
                    1, Long.parseLong(versionId), new SkillVersionService.PublishRequest("1", "1.0.1", List.of()))).status());
            assertEquals(409, assertThrows(SkillException.class,
                    () -> harness.admin.delete("meeting-booking")).status());
        }
    }

    @Test
    void brokenPackageIsKeptAndOversizedUploadIsRejected() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(1, null, 0, true);
            SkillImportView broken = harness.imports.upload(1, "bad", "notes.md", "没有 frontmatter".getBytes(StandardCharsets.UTF_8)).view();
            assertEquals("FAILED", broken.state());
            assertEquals("FAIL", broken.checks().get(0).result());
            assertEquals(0, harness.admin.list(null, "all", 1, 20).total());
            byte[] huge = new byte[SkillDocuments.SKILL_MD_LIMIT + 1];
            SkillException tooBig = assertThrows(SkillException.class,
                    () -> harness.imports.upload(1, "huge", "notes.md", huge));
            assertEquals(413, tooBig.status());
            assertEquals(1, harness.jdbc.queryForObject("SELECT COUNT(*) FROM ai_skill_import", Integer.class));
        }
    }

    @Test
    void pendingDependencyCanBeDraftedButBlocksPublish() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(1, null, 0, true);
            SkillImportView uploaded = harness.imports.upload(1, "pend", "meeting.md",
                    markdown("calendar-sync", "同步日历", "正文", """
                            dependencies:
                              - source: calendar.get_freebusy
                                capability: calendar.read
                                required: true
                                gate: publish
                            """)).view();
            SkillImportView checked = harness.imports.configure(
                    1, Long.parseLong(uploaded.id()), uploaded.revision(), "calendar-sync",
                    "PLATFORM_ENTRY", "PRESERVE_PACKAGE", "0", null);
            SkillImportView mapped = harness.imports.saveDependencies(1, Long.parseLong(checked.id()), checked.revision(),
                    List.of(new SkillImportService.DependencyInput(
                            "calendar.get_freebusy", "UNRESOLVED", null, null, "PENDING", "服务尚未接入")));
            SkillImportView confirmed = harness.imports.confirm(
                    1, Long.parseLong(mapped.id()), mapped.revision(), false, List.of());
            long versionId = Long.parseLong(confirmed.result().versionId());
            SkillVersionView draft = harness.versions.get(versionId);
            assertFalse(harness.versions.validate(versionId, draft.revision()).canPublish());
            assertEquals(422, assertThrows(SkillException.class, () -> harness.versions.publish(
                    1, versionId, new SkillVersionService.PublishRequest(draft.revision(), "1.0.0", List.of()))).status());
            assertEquals(0, harness.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agentscope_skills WHERE name = 'calendar-sync'", Integer.class));
        }
    }

    @Test
    void baselineKeepsRuntimeAndCanBeRerun() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(7, null, 0, true);
            harness.admin.create(7, new SkillDraft("weekly-report", "周报", "旧正文", "2", null, "1",
                    List.of(new SkillDraft.SkillFile("references/faq.md", "问答"))));
            assertEquals(1, harness.baselines.migrate(1));
            assertEquals(0, harness.baselines.migrate(1));
            assertEquals("1", harness.admin.detail("weekly-report").status());
            assertEquals(7L, harness.jdbc.queryForObject(
                    "SELECT create_by FROM agentscope_skills WHERE name = 'weekly-report'", Long.class));
            assertEquals(7L, harness.jdbc.queryForObject(
                    "SELECT created_by FROM ai_skill_version WHERE skill_name = 'weekly-report'", Long.class));
            assertNull(harness.jdbc.queryForObject(
                    "SELECT package_version FROM ai_skill_version WHERE skill_name = 'weekly-report'", String.class));
            SkillVersionView.Page drafts = harness.versions.list("weekly-report", "PUBLISHED", 1, 20);
            SkillVersionView copy = harness.versions.create(1, new SkillVersionService.CreateRequest(
                    "weekly-report", drafts.items().get(0).id(), null, null, null, null, null));
            assertEquals("7", harness.jdbc.queryForObject(
                    "SELECT created_by FROM ai_skill_version WHERE id = " + copy.id(), Long.class).toString());
            assertEquals("旧正文", harness.admin.detail("weekly-report").content());
        }
    }

    @Test
    void revisionConflictDoesNotOverwrite() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(1, null, 0, true);
            SkillImportView uploaded = harness.imports.upload(
                    1, "rev", "meeting.md", markdown("meeting-booking", "预约", "正文", null)).view();
            harness.imports.configure(1, Long.parseLong(uploaded.id()), uploaded.revision(),
                    "meeting-booking", "KEEP_ORIGINAL", "PRESERVE_PACKAGE", "0", null);
            SkillException conflict = assertThrows(SkillException.class, () -> harness.imports.configure(
                    1, Long.parseLong(uploaded.id()), uploaded.revision(),
                    "meeting-booking", "PLATFORM_ENTRY", "PRESERVE_PACKAGE", "0", null));
            assertEquals("REVISION_CONFLICT", conflict.code());
            assertEquals("KEEP_ORIGINAL", harness.imports.get(1, Long.parseLong(uploaded.id())).configuration().entryPolicy());
        }
    }

    private static byte[] markdown(String name, String description, String body, String extra) {
        String front = extra == null ? "" : extra.strip() + "\n";
        return ("""
                ---
                name: %s
                description: %s
                %s---

                %s
                """.formatted(name, description, front, body)).getBytes(StandardCharsets.UTF_8);
    }
}
