package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.exception.skill.SkillException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** 导入和版本共用的标识、摘要和冲突判断。 */
public final class SkillSupport {
    private SkillSupport() {}

    public static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    public static boolean duplicate(Throwable error) {
        while (error != null) {
            String message = error.getMessage();
            if (message != null && (message.contains("Unique") || message.contains("UNIQUE")
                    || message.contains("Duplicate") || message.contains("duplicate")
                    || message.contains("23505"))) {
                return true;
            }
            error = error.getCause();
        }
        return false;
    }

    public static long positiveId(String raw, String code, String message) {
        if (raw == null || raw.isBlank() || raw.length() > 18 || raw.chars().anyMatch(ch -> ch < '0' || ch > '9')) {
            throw new SkillException(404, code, message);
        }
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) throw new SkillException(404, code, message);
            return value;
        } catch (NumberFormatException error) {
            throw new SkillException(404, code, message);
        }
    }

    public static long revision(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 18 || raw.chars().anyMatch(ch -> ch < '0' || ch > '9')) {
            throw new SkillException(400, "INVALID_ARGUMENT", "revision 不合法");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw new SkillException(400, "INVALID_ARGUMENT", "revision 不合法");
        }
    }

    public static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    public static String metadata(String name, String description) {
        return "{\"name\":" + json(name) + ",\"description\":" + json(description) + "}";
    }

    static String json(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\', '"' -> out.append('\\').append(c);
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    public static SkillException conflict(long current) {
        return new SkillException(409, "REVISION_CONFLICT", "记录已被修改，请刷新后重试",
                Map.of("currentRevision", Long.toString(current)));
    }

    public static boolean utf8(byte[] body) {
        if (body == null) return false;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body))
                    .toString();
            return text.indexOf('\0') < 0;
        } catch (java.nio.charset.CharacterCodingException error) {
            return false;
        }
    }
}
