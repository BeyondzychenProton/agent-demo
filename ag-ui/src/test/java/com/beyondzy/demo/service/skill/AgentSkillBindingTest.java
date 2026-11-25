package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.service.skill.support.SkillDraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.exception.skill.SkillException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** session-chat 的绑定是覆盖写。停用和不可见的技能不能新绑上，已经绑上的停用行还在。 */
class AgentSkillBindingTest {

    @Test
    void replaceDedupesAndEmptyListClears() {
        try (SkillAdminHarness harness = people()) {
            create(harness, "meeting-booking", "0", null);
            create(harness, "directory-search", "0", null);
            harness.admin.replaceBindings(1, SkillJdbc.SESSION_AGENT,
                    List.of("meeting-booking", "meeting-booking", "directory-search"));
            harness.admin.replaceBindings(1, SkillJdbc.SESSION_AGENT,
                    List.of("directory-search", "meeting-booking"));
            assertEquals(2, harness.bindings());
            assertNotNull(harness.jdbc.queryForObject(
                    "SELECT gmt_create FROM ai_agent_skill WHERE skill_id = (SELECT id FROM agentscope_skills WHERE name = 'meeting-booking')",
                    java.sql.Timestamp.class));

            harness.admin.replaceBindings(1, SkillJdbc.SESSION_AGENT, List.of());
            assertEquals(0, harness.bindings());
            assertEquals(409, assertThrows(SkillException.class, () -> harness.admin.delete("meeting-booking")).status());
            assertEquals(1, harness.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agentscope_skills WHERE name = 'meeting-booking'", Integer.class));
            assertEquals(404, assertThrows(SkillException.class,
                    () -> harness.admin.replaceBindings(1, "office-assistant", List.of())).status());
        }
    }

    @Test
    void rejectsDisabledAndInvisibleWhileExistingDisabledBindingStays() {
        try (SkillAdminHarness harness = people()) {
            create(harness, "public-skill", "0", null);
            create(harness, "owner-skill", "2", null);
            harness.admin.replaceBindings(1, SkillJdbc.SESSION_AGENT, List.of("public-skill", "owner-skill"));
            harness.admin.setEnabled("public-skill", false);

            harness.admin.replaceBindings(1, SkillJdbc.SESSION_AGENT, List.of("public-skill", "owner-skill"));
            assertEquals(2, harness.bindings());

            create(harness, "unbound-owner", "2", null);
            SkillException hidden = assertThrows(SkillException.class, () -> harness.admin.replaceBindings(
                    2, SkillJdbc.SESSION_AGENT, List.of("unbound-owner")));
            assertEquals(400, hidden.status());
            assertEquals(2, harness.bindings());

            create(harness, "unbound-disabled", "0", null);
            harness.admin.setEnabled("unbound-disabled", false);
            assertEquals(400, assertThrows(SkillException.class, () -> harness.admin.replaceBindings(
                    1, SkillJdbc.SESSION_AGENT, List.of("public-skill", "owner-skill", "unbound-disabled"))).status());
            assertEquals(2, harness.bindings());

            assertEquals(409, assertThrows(SkillException.class, () -> harness.admin.delete("public-skill")).status());
            assertEquals(1, harness.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_agent_skill b JOIN agentscope_skills s ON s.id = b.skill_id WHERE s.name = 'public-skill'",
                    Integer.class));
        }
    }

    private static SkillAdminHarness people() {
        SkillAdminHarness harness = SkillAdminHarness.open();
        harness.department(10, null, "甲");
        harness.department(20, null, "乙");
        harness.user(1, 101L, 10, true);
        harness.user(2, 102L, 20, false);
        return harness;
    }

    private static void create(SkillAdminHarness harness, String name, String scope, Long departmentId) {
        harness.admin.create(1, new SkillDraft(name, "说明", "正文", scope, departmentId, "0", List.of()));
    }
}
