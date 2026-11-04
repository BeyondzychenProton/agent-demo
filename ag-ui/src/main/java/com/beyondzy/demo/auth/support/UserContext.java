package com.beyondzy.demo.auth.support;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 请求内当前用户 ID（字符串形式，供 AgentScope RuntimeContext 使用）。
 * Web 边界在切换到 boundedElastic 前写入；单测构造 ChatSessions 时回落到默认 userId。
 */
public final class UserContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() {}

    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static String require() {
        String value = CURRENT.get();
        if (value == null || value.isBlank())
            throw new IllegalStateException("当前请求未绑定用户");
        return value;
    }

    public static <T> T call(String userId, Supplier<T> action) {
        String previous = CURRENT.get();
        CURRENT.set(userId);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void run(String userId, Runnable action) {
        call(userId, () -> {
            action.run();
            return null;
        });
    }
}
