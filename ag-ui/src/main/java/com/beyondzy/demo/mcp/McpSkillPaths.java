package com.beyondzy.demo.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 解析会议技能目录。外部目录里已有 SKILL.md 时用外部目录；否则把内置 meeting-booking 解到工作区。
 */
public final class McpSkillPaths {

    private static final String BUNDLED = "/skills/meeting-booking/SKILL.md";

    private McpSkillPaths() {}

    public record SkillRoot(Path directory, String source) {}

    public static SkillRoot resolve(Path configured, Path workspace) throws IOException {
        if (workspace == null) throw new IllegalArgumentException("工作区不能为空");
        SkillRoot external = externalRoot(configured, workspace);
        if (external != null) return external;
        return bundled(workspace);
    }

    private static SkillRoot externalRoot(Path configured, Path workspace) throws IOException {
        if (configured == null || !Files.isDirectory(configured)) return null;
        if (containsChildSkill(configured)) return new SkillRoot(configured.toAbsolutePath().normalize(), "external");
        if (!Files.isRegularFile(configured.resolve("SKILL.md"))) return null;
        Path mount = workspace.resolve("local-mcp-skill-mount");
        replaceMount(mount);
        Path name = configured.getFileName() == null ? Path.of("meeting-booking") : configured.getFileName();
        Path child = mount.resolve(name.toString());
        try {
            Files.createSymbolicLink(child, configured.toAbsolutePath().normalize());
        } catch (IOException | UnsupportedOperationException error) {
            copyTree(configured, child);
        }
        return new SkillRoot(mount, "external");
    }

    private static SkillRoot bundled(Path workspace) throws IOException {
        Path root = workspace.resolve("local-mcp-skills");
        Path skill = root.resolve("meeting-booking");
        Files.createDirectories(skill);
        try (InputStream in = McpSkillPaths.class.getResourceAsStream(BUNDLED)) {
            if (in == null) throw new IOException("缺少内置会议技能");
            Files.copy(in, skill.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING);
        }
        return new SkillRoot(root, "bundled");
    }

    private static boolean containsChildSkill(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child) && Files.isRegularFile(child.resolve("SKILL.md"))) return true;
            }
        }
        return false;
    }

    private static void replaceMount(Path mount) throws IOException {
        if (Files.exists(mount)) {
            List<Path> paths;
            try (Stream<Path> walk = Files.walk(mount)) {
                paths = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : paths) Files.deleteIfExists(path);
        }
        Files.createDirectories(mount);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
