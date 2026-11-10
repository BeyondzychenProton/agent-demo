package com.beyondzy.demo.auth.support;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 请求线程上捕获的 rawSessionToken（提交任务时写入 Run 作用域）。
 * 不进入 GovernanceClient 实例字段；异步执行改读 {@code RunCredentialRegistry}。
 */
public final class SubmitCredentialCapture {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SubmitCredentialCapture() {}

    public static Optional<String> current() {
        String value = CURRENT.get();
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(value);
    }

    public static <T> T call(String rawSessionToken, Supplier<T> action) {
        String previous = CURRENT.get();
        if (rawSessionToken == null || rawSessionToken.isBlank()) CURRENT.remove();
        else CURRENT.set(rawSessionToken);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
