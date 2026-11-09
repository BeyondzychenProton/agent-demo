package com.beyondzy.demo.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把治理工具名转成当前模型可接受的函数名。
 *
 * <p>AgentScope 2.1.0-BETA1 的 Toolkit 不限制字符，但 OpenAI 兼容接口要求
 * {@code [A-Za-z0-9_-]{1,64}}。点号会变成下划线。重名、超长或与本地工具冲突时，
 * 用工具 URI 的 SHA-256 前 8 位消歧，URI 本身保存在执行器里，不从名称反推。
 */
public final class GovernanceToolNames {

    public static final int MAX_LENGTH = 64;
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> LOCAL_TOOLS = Set.of("lookup_order", "search_docs", "write_note");

    private GovernanceToolNames() {}

    public record Assignment(String registeredName, String uri, String sourceName) {}

    public static List<Assignment> assign(List<GovernanceToolView> tools) {
        List<String> bases = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        for (GovernanceToolView tool : tools) {
            String base = sanitize(tool.name());
            bases.add(base);
            counts.merge(base, 1, Integer::sum);
        }
        Set<String> used = new HashSet<>(LOCAL_TOOLS);
        List<Assignment> assigned = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            GovernanceToolView tool = tools.get(i);
            String base = bases.get(i);
            boolean plain = counts.get(base) == 1
                    && !LOCAL_TOOLS.contains(base)
                    && SAFE.matcher(base).matches()
                    && !used.contains(base);
            String registered = plain ? base : disambiguate(base, tool.uri(), used);
            used.add(registered);
            assigned.add(new Assignment(registered, tool.uri(), tool.name()));
        }
        return assigned;
    }

    public static String digest8(String uri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((uri == null ? "" : uri).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String sanitize(String name) {
        if (name == null || name.isBlank()) return "tool";
        StringBuilder value = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint <= 0x7F) {
                char c = (char) codePoint;
                if (isSafe(c)) value.append(c);
                else value.append('_');
            } else {
                value.append('_');
            }
        }
        String sanitized = value.toString();
        if (sanitized.isBlank() || sanitized.chars().allMatch(ch -> ch == '_')) return "tool";
        return sanitized;
    }

    private static String disambiguate(String base, String uri, Set<String> used) {
        String suffix = "_" + digest8(uri);
        String stem = base == null || base.isBlank() ? "tool" : base;
        if (stem.length() + suffix.length() > MAX_LENGTH) {
            stem = stem.substring(0, MAX_LENGTH - suffix.length());
        }
        String candidate = stem + suffix;
        int serial = 2;
        while (used.contains(candidate) || !SAFE.matcher(candidate).matches()) {
            String tail = "_" + serial++;
            String head = stem + suffix;
            if (head.length() + tail.length() > MAX_LENGTH) head = head.substring(0, MAX_LENGTH - tail.length());
            candidate = head + tail;
            if (serial > 1000) throw new IllegalStateException("无法为治理工具分配名称");
        }
        return candidate;
    }

    private static boolean isSafe(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-';
    }
}
