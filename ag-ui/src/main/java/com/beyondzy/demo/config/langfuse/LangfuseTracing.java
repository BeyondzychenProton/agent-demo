package com.beyondzy.demo.config.langfuse;

import io.agentscope.core.middleware.MiddlewareBase;
import java.util.List;

/** 挂到 HarnessAgent 上的 Langfuse 观测 middleware。未启用时列表为空。 */
public final class LangfuseTracing {
    private final List<MiddlewareBase> middlewares;

    public LangfuseTracing(List<MiddlewareBase> middlewares) {
        this.middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
    }

    public static LangfuseTracing disabled() {
        return new LangfuseTracing(List.of());
    }

    public List<MiddlewareBase> middlewares() {
        return middlewares;
    }
}
