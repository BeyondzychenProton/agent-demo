package com.beyondzy.demo.governance;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 出站治理调用的用户会话凭证作用域（ThreadLocal）。
 *
 * <p>禁止写入 {@link GovernanceClient} 实例字段；invoke 每请求从此处读取。
 * 异步工具线程须在 callAsync 前后 bind/clear，不能依赖 Web SecurityContext。
 */
public final class OutboundUserCredential {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private OutboundUserCredential() {}

    public static Optional<String> current() {
        String value = CURRENT.get();
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(value);
    }

    public static void bind(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) CURRENT.remove();
        else CURRENT.set(rawSessionToken);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T call(String rawSessionToken, Supplier<T> action) {
        String previous = CURRENT.get();
        bind(rawSessionToken);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
