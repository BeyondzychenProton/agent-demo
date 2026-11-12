package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.enums.skill.SkillFileKind;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 把上传字节解析成一份技能。调用方不能指定 source。 */
public final class SkillDocuments {
    public static final int SKILL_MD_LIMIT = 256 * 1024;
    public static final int RESOURCE_LIMIT = 512 * 1024;
    public static final int ARCHIVE_LIMIT = 2 * 1024 * 1024;
    public static final int MAX_FILES = 50;
    public static final int TOTAL_TEXT_LIMIT = 2 * 1024 * 1024;
    public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    public record Parsed(
            String name,
            String description,
            String skillContent,
            Map<String, Object> metadata,
            Map<String, String> resources) {}

    private SkillDocuments() {}

    public static Parsed markdown(byte[] body) {
        if (body == null || body.length == 0) fail(400, "SKILL.md 为空");
        if (body.length > SKILL_MD_LIMIT) fail(400, "SKILL.md 超过 256KB");
        return parse(decode(body), Map.of());
    }

    public static Parsed archive(byte[] zip) {
        if (zip == null || zip.length == 0) fail(400, "压缩包为空");
        if (zip.length > ARCHIVE_LIMIT) fail(400, "压缩包超过 2MB");
        List<SkillUpload> files = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            int total = 0;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (files.size() >= MAX_FILES) fail(400, "技能文件超过 50 个");
                int room = TOTAL_TEXT_LIMIT - total;
                byte[] body = readLimited(in, room);
                total += body.length;
                files.add(new SkillUpload(entry.getName(), body));
            }
        } catch (SkillException error) {
            throw error;
        } catch (IOException error) {
            throw new SkillException(400, "压缩包无法读取");
        }
        return files(files);
    }

    public static Parsed files(List<SkillUpload> uploads) {
        if (uploads == null || uploads.isEmpty()) fail(400, "没有文件");
        if (uploads.size() > MAX_FILES) fail(400, "技能文件超过 50 个");
        List<SkillUpload> normalized = new ArrayList<>();
        int total = 0;
        for (SkillUpload upload : uploads) {
            if (upload == null || upload.body() == null) fail(400, "文件为空");
            total += upload.body().length;
            if (total > TOTAL_TEXT_LIMIT) fail(400, "文本合计超过 2MB");
            normalized.add(new SkillUpload(normalize(upload.filename()), upload.body()));
        }
        normalized = stripSharedRoot(normalized);
        String skill = null;
        Map<String, String> resources = new LinkedHashMap<>();
        for (SkillUpload upload : normalized) {
            String path = upload.filename();
            rejectShape(path);
            SkillFileKind kind = kind(path);
            if (kind == SkillFileKind.SKILL) {
                if (skill != null) fail(400, "只能有一个 SKILL.md");
                if (upload.body().length > SKILL_MD_LIMIT) fail(400, "SKILL.md 超过 256KB");
                skill = decode(upload.body());
            } else {
                if (upload.body().length > RESOURCE_LIMIT) fail(400, "资源超过 512KB: " + path);
                if (resources.containsKey(path)) fail(400, "重复的文件: " + path);
                resources.put(path, decode(upload.body()));
            }
        }
        if (skill == null) fail(400, "缺少 SKILL.md");
        return parse(skill, resources);
    }

    private static List<SkillUpload> stripSharedRoot(List<SkillUpload> files) {
        String root = null;
        for (SkillUpload file : files) {
            String path = file.filename();
            if ("SKILL.md".equals(path) || path.startsWith("references/") || path.startsWith("scripts/")) {
                return files;
            }
            int slash = path.indexOf('/');
            if (slash <= 0) return files;
            String first = path.substring(0, slash);
            if ("references".equals(first) || "scripts".equals(first) || "reference".equals(first)
                    || "examples".equals(first)) {
                return files;
            }
            if (root == null) root = first;
            else if (!root.equals(first)) return files;
        }
        if (root == null) return files;
        List<SkillUpload> stripped = new ArrayList<>();
        for (SkillUpload file : files) {
            stripped.add(new SkillUpload(file.filename().substring(root.length() + 1), file.body()));
        }
        return stripped;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) fail(400, "文件名为空");
        if (raw.indexOf('\0') >= 0) fail(400, "路径包含 NUL");
        String path = raw.replace('\\', '/').trim();
        while (path.startsWith("./")) path = path.substring(2);
        rejectShape(path);
        return path;
    }

    private static void rejectShape(String path) {
        if (path.indexOf('\0') >= 0) fail(400, "路径包含 NUL");
        if (path.startsWith("/") || path.startsWith("\\")) fail(400, "不允许绝对路径: " + path);
        if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
            fail(400, "不允许盘符路径: " + path);
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) fail(400, "不允许的路径: " + path);
            if ("reference".equals(part) || "examples".equals(part)) fail(400, "不接受的目录: " + path);
        }
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        if (max < 0) fail(400, "解压后的文本超过 2MB");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (out.size() + read > max) fail(400, "解压后的文本超过 2MB");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static Parsed parse(String skillMarkdown, Map<String, String> resources) {
        MarkdownSkillParser.ParsedMarkdown parsed;
        try {
            parsed = MarkdownSkillParser.parse(skillMarkdown);
        } catch (RuntimeException error) {
            throw new SkillException(400, "SKILL.md frontmatter 无法解析");
        }
        if (parsed == null || !parsed.hasFrontmatter()) fail(400, "SKILL.md 缺少 frontmatter");
        Map<String, Object> raw = parsed.getMetadata() == null ? Map.of() : parsed.getMetadata();
        String name = requiredText(raw.get("name"), "name");
        if (!NAME.matcher(name).matches()) fail(400, "技能名不合法: " + name);
        String description = requiredText(raw.get("description"), "description");
        // 框架构造 AgentSkill 时要求 metadata 里带 name 和 description，保存时整份写入 metadata_json。
        Map<String, Object> metadata = new LinkedHashMap<>(raw);
        metadata.put("name", name);
        metadata.put("description", description);
        return new Parsed(name, description, skillMarkdown, metadata, Map.copyOf(resources));
    }

    static String decode(byte[] body) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            if (text.indexOf('\0') >= 0) fail(400, "文件包含 NUL");
            return text;
        } catch (CharacterCodingException error) {
            throw new SkillException(400, "文件不是 UTF-8 文本");
        }
    }

    public static SkillFileKind kind(String relativePath) {
        if ("SKILL.md".equals(relativePath)) return SkillFileKind.SKILL;
        if (relativePath.startsWith("references/") && relativePath.length() > "references/".length())
            return SkillFileKind.REFERENCE;
        if (relativePath.startsWith("scripts/") && relativePath.length() > "scripts/".length())
            return SkillFileKind.SCRIPT;
        return fail(400, "不接受的技能文件: " + relativePath);
    }

    private static String requiredText(Object value, String field) {
        if (value instanceof String text && !text.isBlank()) return text.trim();
        return fail(400, "frontmatter 缺少 " + field);
    }

    private static <T> T fail(int status, String message) {
        throw new SkillException(status, message);
    }
}
