package com.beyondzy.demo.service.skill.support;

import com.beyondzy.demo.enums.skill.SkillContentPolicy;
import com.beyondzy.demo.enums.skill.SkillEntryPolicy;
import com.beyondzy.demo.enums.skill.SkillFileKind;
import com.beyondzy.demo.exception.skill.SkillException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 解析上传包并生成检查结果。不写数据库。
 *
 * <p>依赖和规则读 SKILL.md frontmatter 里的 dependencies、rules、policies、version。
 * 结构损坏只返回报告，调用方决定是否留下批次。
 */
public final class SkillPackageInspector {
    public static final String VERSION = "2026-11-23";

    public record FileBytes(String path, SkillFileKind kind, byte[] body) {}

    public record SourceDependency(String sourceKey, String capabilityKey, boolean required, String gate) {}

    public record SourceRule(String key, String level, String message, boolean acknowledge, String blocks) {}

    public record Check(String ruleKey, String level, String result, String message, boolean acknowledge) {}

    public record Inspection(
            boolean structural,
            String errorCode,
            String message,
            String name,
            String packageVersion,
            String description,
            String skillMarkdown,
            List<FileBytes> files,
            List<SourceDependency> dependencies,
            List<SourceRule> rules,
            Set<String> allowedEntry,
            Set<String> allowedContent,
            List<Check> checks) {}

    private SkillPackageInspector() {}

    public static Inspection inspect(String filename, byte[] body) {
        try {
            Parsed parsed = read(filename, body);
            List<Check> checks = List.of(new Check(
                    "PACKAGE_STRUCTURE", "ERROR", "PASS", "包结构通过", false));
            return new Inspection(false, null, null, parsed.name, parsed.version, parsed.description,
                    parsed.markdown, List.copyOf(parsed.files), List.copyOf(parsed.dependencies),
                    List.copyOf(parsed.rules), Set.copyOf(parsed.allowedEntry), Set.copyOf(parsed.allowedContent),
                    checks);
        } catch (SkillException error) {
            return new Inspection(true, "PACKAGE_DAMAGED", error.getMessage(), null, null, null, null,
                    List.of(), List.of(), List.of(), Set.of(), Set.of(),
                    List.of(new Check("PACKAGE_STRUCTURE", "ERROR", "FAIL", error.getMessage(), false)));
        }
    }

    /** 按策略生成拟发布入口。不支持的组合直接拒绝，不另造一份入口。 */
    public static String propose(Inspection inspection, String targetName, SkillEntryPolicy entry, SkillContentPolicy content) {
        if (inspection.structural()) {
            throw new SkillException(422, "CHECK_BLOCKED", "包结构损坏，请重新上传");
        }
        if (!inspection.allowedEntry().contains(entry.name()) || !inspection.allowedContent().contains(content.name())) {
            throw new SkillException(422, "POLICY_UNSUPPORTED", "原包不允许这组处理策略");
        }
        if (entry == SkillEntryPolicy.KEEP_ORIGINAL && content == SkillContentPolicy.REFERENCE_ONLY) {
            throw new SkillException(422, "POLICY_UNSUPPORTED", "保留原入口不能同时只按参考资料处理");
        }
        if (entry == SkillEntryPolicy.KEEP_ORIGINAL) {
            if (targetName.equals(inspection.name())) return inspection.skillMarkdown();
            return inspection.skillMarkdown().replaceFirst("(?m)^name:\\s*.*$", "name: " + quote(targetName));
        }
        String body = SkillDocuments.body(inspection.skillMarkdown());
        if (content == SkillContentPolicy.REFERENCE_ONLY) {
            StringBuilder note = new StringBuilder(body);
            if (!note.isEmpty() && note.charAt(note.length() - 1) != '\n') note.append('\n');
            note.append("\n平台仅保留资料，不执行包内脚本。\n");
            for (FileBytes file : inspection.files()) {
                if (file.kind() == SkillFileKind.SKILL) continue;
                note.append("- ").append(file.path()).append('\n');
            }
            body = note.toString();
        }
        return SkillDocuments.compose(targetName, inspection.description(), body);
    }

    private static Parsed read(String filename, byte[] body) {
        if (body == null || body.length == 0) fail("文件为空");
        boolean zip = zipName(filename) || looksZip(body);
        if (markdownName(filename) && !looksZip(body)) {
            if (body.length > SkillDocuments.SKILL_MD_LIMIT) fail("SKILL.md 超过 256KB");
            return finish(List.of(new FileBytes("SKILL.md", SkillFileKind.SKILL, body)));
        }
        if (!zip) fail("只接受 Markdown 或 ZIP");
        if (body.length > SkillDocuments.ARCHIVE_LIMIT) fail("压缩包超过 2MB");
        return finish(unzip(body));
    }

    private static List<FileBytes> unzip(byte[] zip) {
        List<FileBytes> files = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            int total = 0;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (files.size() >= SkillDocuments.MAX_FILES) fail("技能文件超过 50 个");
                int room = SkillDocuments.TOTAL_TEXT_LIMIT - total;
                byte[] content = readLimited(in, room);
                total += content.length;
                files.add(new FileBytes(entry.getName(), null, content));
            }
        } catch (SkillException error) {
            throw error;
        } catch (IOException error) {
            fail("压缩包无法读取");
        }
        if (files.isEmpty()) fail("压缩包里没有文件");
        return files;
    }

    private static Parsed finish(List<FileBytes> raw) {
        List<FileBytes> files = normalize(stripRoot(cleanNames(raw)));
        String markdown = null;
        Set<String> paths = new LinkedHashSet<>();
        int total = 0;
        for (FileBytes file : files) {
            total += file.body().length;
            if (total > SkillDocuments.TOTAL_TEXT_LIMIT) fail("解压后的文本超过 2MB");
            if (!paths.add(file.path().toLowerCase(Locale.ROOT))) fail("文件路径大小写冲突: " + file.path());
            if (file.kind() == SkillFileKind.SKILL) {
                if (markdown != null) fail("只能有一个 SKILL.md");
                if (file.body().length > SkillDocuments.SKILL_MD_LIMIT) fail("SKILL.md 超过 256KB");
                markdown = SkillDocuments.decode(file.body());
            } else if (file.body().length > SkillDocuments.RESOURCE_LIMIT) {
                fail("资源超过 512KB: " + file.path());
            } else {
                SkillDocuments.decode(file.body());
            }
        }
        if (markdown == null) fail("缺少 SKILL.md");
        SkillDocuments.Parsed parsed = SkillDocuments.parse(markdown, Map.of());
        String front = frontmatter(markdown);
        String version = scalar(front, "version");
        if (version != null && version.length() > 64) fail("包版本过长");
        List<SourceDependency> dependencies = dependencies(front);
        List<SourceRule> rules = rules(front);
        Set<String> entry = policies(front, "entry", SkillEntryPolicy.KEEP_ORIGINAL.name(), SkillEntryPolicy.PLATFORM_ENTRY.name());
        Set<String> content = policies(front, "content", SkillContentPolicy.PRESERVE_PACKAGE.name(), SkillContentPolicy.REFERENCE_ONLY.name());
        return new Parsed(parsed.name(), version, parsed.description(), markdown, files, dependencies, rules, entry, content);
    }

    private static List<FileBytes> cleanNames(List<FileBytes> files) {
        List<FileBytes> cleaned = new ArrayList<>();
        for (FileBytes file : files) {
            String path = file.path() == null ? "" : file.path().replace('\\', '/').trim();
            while (path.startsWith("./")) path = path.substring(2);
            cleaned.add(new FileBytes(path, null, file.body()));
        }
        return cleaned;
    }

    private static List<FileBytes> normalize(List<FileBytes> files) {
        if (files.size() > SkillDocuments.MAX_FILES) fail("技能文件超过 50 个");
        List<FileBytes> normalized = new ArrayList<>();
        for (FileBytes file : files) {
            String path = file.path().replace('\\', '/').trim();
            while (path.startsWith("./")) path = path.substring(2);
            reject(path);
            SkillFileKind kind = SkillDocuments.kind(path);
            normalized.add(new FileBytes(path, kind, file.body()));
        }
        return normalized;
    }

    private static List<FileBytes> stripRoot(List<FileBytes> files) {
        String root = null;
        for (FileBytes file : files) {
            String path = file.path();
            if ("SKILL.md".equals(path) || path.startsWith("references/") || path.startsWith("scripts/")) return files;
            int slash = path.indexOf('/');
            if (slash <= 0) return files;
            String first = path.substring(0, slash);
            if (root == null) root = first;
            else if (!root.equals(first)) return files;
        }
        if (root == null) return files;
        List<FileBytes> stripped = new ArrayList<>();
        for (FileBytes file : files) stripped.add(new FileBytes(file.path().substring(root.length() + 1), null, file.body()));
        return stripped;
    }

    private static void reject(String path) {
        if (path.indexOf('\0') >= 0) fail("路径包含 NUL");
        if (path.startsWith("/") || path.startsWith("\\")) fail("不允许绝对路径: " + path);
        if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) fail("不允许盘符路径: " + path);
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) fail("不允许的路径: " + path);
        }
    }

    private static byte[] readLimited(java.io.InputStream in, int max) throws IOException {
        if (max < 0) fail("解压后的文本超过 2MB");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (out.size() + read > max) fail("解压后的文本超过 2MB");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static List<SourceDependency> dependencies(String front) {
        List<SourceDependency> dependencies = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, String> item : items(front, "dependencies")) {
            String source = required(item, "source");
            if (!source.matches("[A-Za-z0-9_.:\\-]{1,128}")) fail("依赖标识不合法: " + source);
            if (!keys.add(source)) fail("重复的依赖: " + source);
            String capability = emptyToNull(item.get("capability"));
            if (capability != null && capability.length() > 128) fail("能力标识过长: " + source);
            boolean required = !"false".equalsIgnoreCase(item.getOrDefault("required", "true"))
                    && !"0".equals(item.get("required"));
            String gate = item.getOrDefault("gate", "publish");
            if (!"confirm".equals(gate) && !"publish".equals(gate)) fail("依赖 gate 不合法: " + source);
            dependencies.add(new SourceDependency(source, capability, required, gate));
        }
        return dependencies;
    }

    private static List<SourceRule> rules(String front) {
        List<SourceRule> rules = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, String> item : items(front, "rules")) {
            String key = required(item, "key");
            if (!key.matches("[A-Z0-9_]{1,64}")) fail("规则标识不合法: " + key);
            if (!keys.add(key)) fail("重复的规则: " + key);
            String level = item.getOrDefault("level", "WARN");
            if (!"ERROR".equals(level) && !"WARN".equals(level)) fail("规则级别不合法: " + key);
            boolean acknowledge = item.containsKey("acknowledge")
                    ? Boolean.parseBoolean(item.get("acknowledge"))
                    : "WARN".equals(level);
            if ("ERROR".equals(level)) acknowledge = false;
            String blocks = item.getOrDefault("blocks", "ERROR".equals(level) ? "both" : "confirm");
            if (!Set.of("confirm", "publish", "both", "none").contains(blocks)) fail("规则阻断范围不合法: " + key);
            String message = item.getOrDefault("message", key);
            rules.add(new SourceRule(key, level, message, acknowledge, blocks));
        }
        return rules;
    }

    private static Set<String> policies(String front, String field, String... defaults) {
        Map<String, String> block = nested(front, "policies");
        if (!block.containsKey(field)) return Set.of(defaults);
        Set<String> allowed = new LinkedHashSet<>();
        for (String part : block.get(field).split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) allowed.add(value);
        }
        if (allowed.isEmpty()) return Set.of(defaults);
        return allowed;
    }

    private static String frontmatter(String markdown) {
        if (!markdown.startsWith("---")) return "";
        int close = markdown.indexOf("\n---", 3);
        if (close < 0) return "";
        return markdown.substring(3, close);
    }

    private static String scalar(String front, String key) {
        for (String line : front.split("\n")) {
            if (line.startsWith(key + ":")) {
                String value = unquote(line.substring(key.length() + 1).trim());
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static Map<String, String> nested(String front, String key) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean inside = false;
        for (String raw : front.split("\n")) {
            if (!inside) {
                if (raw.equals(key + ":") || raw.startsWith(key + ":")) inside = raw.substring(key.length() + 1).isBlank();
                continue;
            }
            if (raw.isBlank()) continue;
            if (!Character.isWhitespace(raw.charAt(0))) break;
            String line = raw.strip();
            if (line.startsWith("- ")) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            values.put(line.substring(0, colon).trim(), unquote(line.substring(colon + 1).trim()));
        }
        return values;
    }

    private static List<Map<String, String>> items(String front, String key) {
        List<Map<String, String>> items = new ArrayList<>();
        boolean inside = false;
        Map<String, String> current = null;
        for (String raw : front.split("\n")) {
            if (!inside) {
                if (raw.equals(key + ":")) inside = true;
                continue;
            }
            if (raw.isBlank()) continue;
            if (!Character.isWhitespace(raw.charAt(0))) break;
            String line = raw.strip();
            if (line.startsWith("- ")) {
                current = new LinkedHashMap<>();
                items.add(current);
                put(current, line.substring(2).trim());
            } else if (current != null) {
                put(current, line);
            }
        }
        return items;
    }

    private static void put(Map<String, String> item, String pair) {
        int colon = pair.indexOf(':');
        if (colon <= 0) return;
        item.put(pair.substring(0, colon).trim(), unquote(pair.substring(colon + 1).trim()));
    }

    private static String required(Map<String, String> item, String key) {
        String value = item.get(key);
        if (value == null || value.isBlank()) fail("依赖缺少 " + key);
        return value.trim();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String quote(String name) {
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static boolean markdownName(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    public static boolean zipName(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static boolean looksZip(byte[] body) {
        return body != null && body.length >= 2 && body[0] == 'P' && body[1] == 'K';
    }

    private static void fail(String message) {
        throw new SkillException(400, message);
    }

    private record Parsed(
            String name,
            String version,
            String description,
            String markdown,
            List<FileBytes> files,
            List<SourceDependency> dependencies,
            List<SourceRule> rules,
            Set<String> allowedEntry,
            Set<String> allowedContent) {}
}
