package com.beyondzy.demo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpSkillPathsTest {

    @Test
    void usesExternalRepositoryWhenChildSkillExists(@TempDir Path temp) throws Exception {
        Path skills = temp.resolve("meeting-booking");
        Files.createDirectories(skills.resolve("book-room"));
        Files.writeString(skills.resolve("book-room").resolve("SKILL.md"), "---\nname: book-room\n---\n");
        McpSkillPaths.SkillRoot root = McpSkillPaths.resolve(skills, temp.resolve("workspace"));
        assertEquals("external", root.source());
        assertEquals(skills.toAbsolutePath().normalize(), root.directory());
    }

    @Test
    void mountsSingleSkillDirectory(@TempDir Path temp) throws Exception {
        Path skill = temp.resolve("meeting-booking");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: meeting-booking\n---\n外部技能\n");
        Path workspace = temp.resolve("workspace");
        McpSkillPaths.SkillRoot root = McpSkillPaths.resolve(skill, workspace);
        assertEquals("external", root.source());
        assertTrue(Files.isRegularFile(root.directory().resolve("meeting-booking").resolve("SKILL.md")));
        assertTrue(Files.readString(root.directory().resolve("meeting-booking").resolve("SKILL.md")).contains("外部技能"));
    }

    @Test
    void fallsBackToBundledSkill(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        McpSkillPaths.SkillRoot root = McpSkillPaths.resolve(temp.resolve("missing"), workspace);
        assertEquals("bundled", root.source());
        String text = Files.readString(root.directory().resolve("meeting-booking").resolve("SKILL.md"));
        assertTrue(text.contains("name: meeting-booking"));
        assertTrue(text.contains("identity_resolve"));
        assertFalse(new FileSystemSkillRepository(root.directory(), false, "local-meeting").getAllSkills().isEmpty());
    }
}
