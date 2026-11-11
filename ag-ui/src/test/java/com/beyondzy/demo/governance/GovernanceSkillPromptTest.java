package com.beyondzy.demo.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GovernanceSkillPromptTest {

    @Test
    void prefersInlineInstructionsOverRef() {
        GovernanceSkillView skill = new GovernanceSkillView(
                "dev-seed-empty",
                "DEV_SEED empty",
                "desc",
                "1",
                "使用本 Skill 时无需绑定工具。",
                "/api/agent/snapshot/snap/skills/dev-seed-empty/instructions",
                "digest",
                40,
                List.of());
        RecordingGateway gateway = new RecordingGateway();
        gateway.instructionsBody = "SHOULD_NOT_FETCH";

        String body = GovernanceSkillPrompt.resolveInstructions(skill, gateway, "snap");
        assertEquals("使用本 Skill 时无需绑定工具。", body);
        assertEquals(0, gateway.instructionFetches);
    }

    @Test
    void fetchesWhenOnlyInstructionsRefPresent() {
        GovernanceSkillView skill = new GovernanceSkillView(
                "big-skill",
                "Big",
                "desc",
                "1",
                null,
                "/api/agent/snapshot/snap_x/skills/big-skill/instructions",
                "digest",
                40000,
                List.of());
        RecordingGateway gateway = new RecordingGateway();
        gateway.instructionsBody = "fetched body for big skill";

        String body = GovernanceSkillPrompt.resolveInstructions(skill, gateway, "snap_x");
        assertEquals("fetched body for big skill", body);
        assertEquals(1, gateway.instructionFetches);
        assertEquals("snap_x", gateway.lastInstructionSnapshotId);
        assertEquals("big-skill", gateway.lastInstructionSkillId);
    }

    @Test
    void buildsSysPromptSectionWithSkillTextAndRegisteredBindingHints() {
        GovernanceSkillView empty = new GovernanceSkillView(
                "dev-seed-empty",
                "DEV_SEED 空清单说明",
                "纯说明",
                "1",
                "使用本 Skill 时无需绑定工具。",
                null,
                "d1",
                20,
                List.of());
        GovernanceSkillView ticket = new GovernanceSkillView(
                "dev-seed-ticket",
                "DEV_SEED 工单助手",
                "绑定 get_ticket",
                "1",
                "查询工单时调用 get_ticket。",
                null,
                "d2",
                30,
                List.of(new GovernanceSkillBinding(
                        "stb_1",
                        "ticket_get_ticket",
                        "get_ticket",
                        "driver://mcp/tenant-a/ticketing/get_ticket")));
        RecordingGateway gateway = new RecordingGateway();
        Map<String, String> uriNames = Map.of(
                "driver://mcp/tenant-a/ticketing/get_ticket", "get_ticket");

        String section = GovernanceSkillPrompt.buildSection(
                List.of(empty, ticket), uriNames, gateway, "snap_live");

        assertTrue(section.contains("dev-seed-empty"));
        assertTrue(section.contains("使用本 Skill 时无需绑定工具。"));
        assertTrue(section.contains("dev-seed-ticket"));
        assertTrue(section.contains("查询工单时调用 get_ticket。"));
        assertTrue(section.contains("get_ticket"));
        assertTrue(section.contains("driver://mcp/tenant-a/ticketing/get_ticket")
                || section.contains("registered"));
        assertEquals(0, gateway.instructionFetches);
    }

    static final class RecordingGateway implements GovernanceGateway {
        String instructionsBody = "";
        int instructionFetches;
        String lastInstructionSnapshotId;
        String lastInstructionSkillId;
        GovernanceSnapshot snapshot = new GovernanceSnapshot(
                "tenant-a", "default", "agent-reader", "snap", List.of());

        @Override
        public GovernanceSnapshot currentSnapshot() {
            return snapshot;
        }

        @Override
        public GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String fetchInstructions(String snapshotId, String skillId) {
            instructionFetches++;
            lastInstructionSnapshotId = snapshotId;
            lastInstructionSkillId = skillId;
            return instructionsBody;
        }
    }
}
