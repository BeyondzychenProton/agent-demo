package com.beyondzy.demo.governance.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.governance.GovernanceGateway;
import com.beyondzy.demo.governance.GovernanceInvokeResult;
import com.beyondzy.demo.governance.GovernanceSnapshot;
import com.beyondzy.demo.governance.GovernanceSnapshotFetch;
import com.beyondzy.demo.governance.GovernanceToolView;
import com.beyondzy.demo.governance.GovernanceSkillBinding;
import com.beyondzy.demo.governance.GovernanceSkillView;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 2：第二宿主单点接入——配置网关后一次 attach 得到工具与版本。 */
class AgentScopeGovernanceAdapterTest {

    @Test
    void attachYieldsToolkitAndSnapshotInOneCall(@TempDir Path workspace) {
        MutableGateway gateway = new MutableGateway(snap("snap_host", "get_ticket", "driver://mcp/t/get_ticket"));
        AgentScopeGovernanceAdapter.AttachedAssembly attached =
                AgentScopeGovernanceAdapter.attach(workspace, gateway);
        assertEquals("snap_host", attached.snapshotId());
        assertTrue(attached.toolNames().contains("get_ticket"));
        assertFalse(attached.toolkit().getTool("get_ticket") == null);
    }

    @Test
    void attachSnapshotRebuildsHistoricalId(@TempDir Path workspace) {
        MutableGateway gateway = new MutableGateway(snap("snap_a", "tool_a", "driver://mcp/t/a"));
        gateway.publish(snap("snap_b", "tool_b", "driver://mcp/t/b"));
        AgentScopeGovernanceAdapter.AttachedAssembly rebuilt =
                AgentScopeGovernanceAdapter.attachSnapshot(workspace, gateway, "snap_a");
        assertEquals("snap_a", rebuilt.snapshotId());
        assertTrue(rebuilt.toolNames().contains("tool_a"));
        assertFalse(rebuilt.toolNames().contains("tool_b"));
    }

    @Test
    void readsBothSkillReferencesAndInvokesBoundToolWithoutUserLogin(@TempDir Path workspace) {
        String uri = "driver://mcp/t/room_recommend";
        GovernanceSnapshot base = snap("snap_meeting", "room_recommend", uri);
        String skillId = "meeting-booking";
        List<String> paths = List.of("references/room-directory.md", "references/tool-contract.md");
        GovernanceSnapshot meeting = new GovernanceSnapshot(
                base.tenantId(), base.appId(), base.subject(), base.snapshotId(), base.tools(),
                List.of(new GovernanceSkillView(skillId, skillId, "", "1", "Read the references.",
                        null, null, null,
                        List.of(new GovernanceSkillBinding("binding", "room_recommend", "find_room", uri)), paths)));
        MutableGateway gateway = new MutableGateway(meeting);
        var assembly = AgentScopeGovernanceAdapter.attach(workspace, gateway);
        assertTrue(assembly.skillsSection().contains("registered=`room_recommend` alias=`find_room`"));
        assertTrue(assembly.toolkit().getTool("lookup_order") == null);
        for (String path : paths) {
            assertTrue(assembly.skillsSection().contains(path));
            call(assembly, "read_governance_skill_resource", Map.of("skillId", skillId, "path", path));
        }
        call(assembly, "room_recommend", Map.of());
        assertEquals(List.of("snap_meeting/meeting-booking/" + paths.get(0),
                "snap_meeting/meeting-booking/" + paths.get(1), "snap_meeting/" + uri), gateway.calls);
    }

    private static void call(AgentScopeGovernanceAdapter.AttachedAssembly assembly, String name,
            Map<String, Object> arguments) {
        var result = assembly.toolkit().getTool(name).callAsync(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder().id(name).name(name).input(arguments)
                                .content(arguments.isEmpty() ? "{}" : "{\"skillId\":\""+ arguments.get("skillId") + "\",\"path\":\"" + arguments.get("path") + "\"}").build())
                        .input(arguments)
                        .build())
                .block(Duration.ofSeconds(5));
        assertEquals("SUCCESS", result.getState().name(), result.toString());
    }

    private static GovernanceSnapshot snap(String id, String name, String uri) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return new GovernanceSnapshot(
                "tenant-a",
                "default",
                "agent-reader",
                id,
                List.of(new GovernanceToolView(uri, name, name, schema, "1:1", "1", "1", "read")));
    }

    private static final class MutableGateway implements GovernanceGateway {
        private final AtomicReference<GovernanceSnapshot> current = new AtomicReference<>();
        private final Map<String, GovernanceSnapshot> history = new LinkedHashMap<>();

        private final java.util.ArrayList<String> calls = new java.util.ArrayList<>();

        MutableGateway(GovernanceSnapshot initial) {
            publish(initial);
        }

        void publish(GovernanceSnapshot snapshot) {
            current.set(snapshot);
            history.put(snapshot.snapshotId(), snapshot);
        }

        @Override
        public GovernanceSnapshot currentSnapshot() {
            return current.get();
        }

        @Override
        public GovernanceSnapshotFetch fetchCurrent(String ifNoneMatch) {
            GovernanceSnapshot snap = current.get();
            return GovernanceSnapshotFetch.of(snap, snap.snapshotId());
        }

        @Override
        public GovernanceSnapshot fetchSnapshot(String snapshotId) {
            return history.get(snapshotId);
        }

        @Override
        public GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments) {
            calls.add(snapshotId + "/" + uri);
            return GovernanceInvokeResult.completed("OK", "ok", Map.of());
        }

        @Override
        public String fetchResource(String snapshotId, String skillId, String relativePath) {
            calls.add(snapshotId + "/" + skillId + "/" + relativePath);
            return "# " + relativePath;
        }

        @Override
        public String fetchInstructions(String snapshotId, String skillId) {
            return "";
        }
    }
}
