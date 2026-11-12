package com.beyondzy.demo.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessNativeSkillProofTest {
    private static final String LOAD_TOOL = "load_skill_through_path";
    private static final List<String> PATHS = List.of(
            "SKILL.md", "references/room-directory.md", "references/tool-contract.md");
    private static final List<String> MARKERS = List.of("BODY_MARKER", "ROOM_MARKER", "CONTRACT_MARKER");

    @Test
    void nativeHarnessDisclosesMetadataThenLoadsBodyAndReferences(@TempDir Path workspace) throws Exception {
        Path repository = workspace.resolve("market");
        Path skill = repository.resolve("meeting-booking");
        Files.createDirectories(skill.resolve("references"));
        Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: meeting-booking\ndescription: Search for meeting rooms\n---\nBODY_MARKER\n");
        Files.writeString(skill.resolve(PATHS.get(1)), MARKERS.get(1));
        Files.writeString(skill.resolve(PATHS.get(2)), MARKERS.get(2));
        RecordingModel model = new RecordingModel();
        run(workspace, new FileSystemSkillRepository(repository, false), model);
        assertTrue(model.requests.size() >= 4);
        String first = model.requests.getFirst();
        assertTrue(first.contains("<available_skills>"), first);
        assertTrue(first.contains("meeting-booking"), first);
        assertTrue(first.contains("Search for meeting rooms"), first);
        assertTrue(first.contains("Stable system prompt."), first);
        for (int index = 0; index < MARKERS.size(); index++) {
            String marker = MARKERS.get(index);
            assertFalse(first.contains(marker), marker);
            String request = model.requests.get(index + 1);
            assertTrue(request.contains(marker), () -> marker + " absent from request:\n" + request);
        }
    }

    @Test
    void nativeLoaderRechecksBoundSnapshotAfterRevocation(@TempDir Path workspace) throws Exception {
        var allowed = new AtomicBoolean(true);
        var reads = new CopyOnWriteArrayList<String>();
        var skill = new GovernanceSkillView("meeting-booking", "Meeting booking", "Search for meeting rooms",
                "1", "INLINE_BODY_MUST_NOT_BE_USED", null, null, null,
                List.of(new GovernanceSkillBinding("binding", "room.search", "find-room", "room-uri")),
                PATHS.subList(1, PATHS.size()));
        var snapshot = new GovernanceSnapshot("tenant", "app", "reader", "snap_v1", List.of(), List.of(skill));
        GovernanceGateway gateway = new GovernanceGateway() {
            @Override
            public GovernanceSnapshot currentSnapshot() { return snapshot; }

            @Override
            public GovernanceInvokeResult invoke(String uri, String id, Map<String, Object> args) {
                throw new AssertionError("Unexpected invoke");
            }

            @Override
            public String fetchInstructions(String id, String skillId) {
                return fetchResource(id, skillId, "SKILL.md");
            }

            @Override
            public String fetchResource(String id, String skillId, String path) {
                assertEquals("meeting-booking", skillId);
                reads.add(id + "/" + path);
                if (!allowed.get()) throw new IllegalStateException("CURRENT_GRANT_REVOKED");
                return MARKERS.get(PATHS.indexOf(path));
            }
        };
        var repository = new GovernanceSkillRepository(gateway, snapshot, Map.of("room-uri", "room_search"));
        var model = new RecordingModel(List.of(PATHS.get(0), PATHS.get(1), PATHS.get(2),
                PATHS.get(0), PATHS.get(1), "../private.md"), step -> {
                    if (step == 0) assertTrue(reads.isEmpty(), "metadata must not fetch content");
                    if (step == 3) allowed.set(false);
                });
        run(workspace, repository, model);
        assertEquals(List.of("snap_v1/SKILL.md", "snap_v1/" + PATHS.get(1), "snap_v1/" + PATHS.get(2),
                "snap_v1/SKILL.md", "snap_v1/" + PATHS.get(1)), reads);
        assertTrue(model.requests.get(1).contains("find-room → `room_search`"));
        assertFalse(model.requests.get(1).contains("INLINE_BODY_MUST_NOT_BE_USED"));
        assertTrue(model.requests.get(4).contains("CURRENT_GRANT_REVOKED"));
        assertTrue(model.requests.get(5).contains("CURRENT_GRANT_REVOKED"));
    }

    private static void run(Path workspace, AgentSkillRepository repository, RecordingModel model) {
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("native-skill-proof")
                .workspace(workspace)
                .sysPrompt("Stable system prompt.")
                .model(model)
                .skillRepository(repository)
                .permissionContext(PermissionContextState.builder()
                        .addAllowRule(LOAD_TOOL, new PermissionRule(
                                LOAD_TOOL, null, PermissionBehavior.ALLOW, "Read test fixture"))
                        .build())
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableSubagents()
                .build()) {
            var session = agent.session(RuntimeContext.builder().userId("reader").sessionId("native-proof").build());
            session.start();
            var task = session.await(session.submit("skill-proof", "Find a meeting room."))
                    .block(Duration.ofSeconds(30));
            assertNotNull(task);
            assertEquals("completed", task.status(), task.error());
        }
    }

    private static final class RecordingModel implements Model {
        private final List<String> requests = new ArrayList<>();
        private final List<String> paths;
        private final java.util.function.IntConsumer beforeStep;

        RecordingModel() {
            this(PATHS, step -> {});
        }

        RecordingModel(List<String> paths, java.util.function.IntConsumer beforeStep) {
            this.paths = paths;
            this.beforeStep = beforeStep;
        }

        @Override
        public String getModelName() {
            return "native-skill-proof";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int step = requests.size();
            beforeStep.accept(step);
            StringBuilder content = new StringBuilder();
            for (Msg message : messages) {
                for (var block : message.getContent()) {
                    if (block instanceof TextBlock text) content.append(text.getText()).append('\n');
                    if (block instanceof ToolResultBlock result) {
                        for (var output : result.getOutput()) {
                            if (output instanceof TextBlock text) content.append(text.getText()).append('\n');
                        }
                    }
                }
            }
            requests.add(content.toString());
            var response = ChatResponse.builder().id("skill-proof-" + step);
            if (step < paths.size()) {
                var skillId = java.util.regex.Pattern.compile("<skill-id>([^<]*meeting-booking[^<]*)</skill-id>")
                        .matcher(requests.getFirst());
                assertTrue(skillId.find(), requests.getFirst());
                Map<String, Object> args = Map.of("skillId", skillId.group(1), "path", paths.get(step));
                return Flux.just(response.content(List.of(ToolUseBlock.builder()
                                .id("load-" + step)
                                .name(LOAD_TOOL)
                                .input(args)
                                .content(JsonUtils.getJsonCodec().toJson(args))
                                .build()))
                        .finishReason("tool_calls")
                        .build());
            }
            return Flux.just(response.content(List.of(TextBlock.builder().text("Done.").build()))
                    .finishReason("stop").build());
        }
    }
}
