package com.beyondzy.demo.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NoteToolsTest {

    @TempDir Path workspace;

    @Test
    void reportsAddedAndDeletedLines() throws IOException {
        NoteTools tools = new NoteTools(workspace);

        Map<?, ?> first = write(tools, "notes/compare.md", "A\nB\n");
        assertEquals(2, first.get("added"));
        assertEquals(0, first.get("deleted"));

        Map<?, ?> extended = write(tools, "notes/compare.md", "A\nB\nC\n");
        assertEquals(1, extended.get("added"));
        assertEquals(0, extended.get("deleted"));

        Map<?, ?> shrunk = write(tools, "notes/compare.md", "A\nC\n");
        assertEquals(0, shrunk.get("added"));
        assertEquals(1, shrunk.get("deleted"));
        assertTrue(workspace.resolve("notes/compare.md").toFile().exists());
    }

    @Test
    void rejectsPathTraversal() {
        NoteTools tools = new NoteTools(workspace);
        assertThrows(IOException.class, () -> tools.writeNote("../outside.md", "x"));
        assertThrows(IOException.class, () -> tools.writeNote("/abs.md", "x"));
    }

    private static Map<?, ?> write(NoteTools tools, String path, String content) throws IOException {
        io.agentscope.core.message.ToolResultBlock result = tools.writeNote(path, content).block();
        String text = ((io.agentscope.core.message.TextBlock) result.getOutput().get(0)).getText();
        return (Map<?, ?>) JsonUtils.getJsonCodec().fromJson(text, Map.class);
    }
}
