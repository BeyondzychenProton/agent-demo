package com.beyondzy.demo.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 用户工作空间：每个 userId 一个目录，非法 id 不得造成路径穿越。 */
class UserWorkspaceServiceTest {

    @TempDir Path root;

    @Test
    void createsOneDirectoryPerUser() {
        UserWorkspaceService workspaces = new UserWorkspaceService(root);

        assertTrue(Files.isDirectory(workspaces.ensure("1")));
        assertTrue(Files.isDirectory(workspaces.ensure("4")));
        assertEquals(root.resolve("1"), workspaces.dirOf("1"));
        assertEquals(root.resolve("4"), workspaces.dirOf("4"));
    }

    @Test
    void isIdempotent() {
        UserWorkspaceService workspaces = new UserWorkspaceService(root);

        workspaces.ensure("1");
        workspaces.ensure("1");

        assertEquals(1, workspaces.root().toFile().list().length);
    }

    @Test
    void rejectsTraversalAndReservedCharacters() {
        UserWorkspaceService workspaces = new UserWorkspaceService(root);

        assertThrows(IllegalArgumentException.class, () -> workspaces.ensure("../etc"));
        assertThrows(IllegalArgumentException.class, () -> workspaces.ensure("a/b"));
        assertThrows(IllegalArgumentException.class, () -> workspaces.ensure("acme:1"));
        assertThrows(IllegalArgumentException.class, () -> workspaces.ensure(null));
    }
}
