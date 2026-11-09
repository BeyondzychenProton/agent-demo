package com.beyondzy.demo.governance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务 arguments 的防御性拷贝。
 *
 * <p>不按字段名猜测或剥离业务含义。外层 {@code uri} / {@code snapshotId} 与
 * {@code X-Agent-Token} 由执行器绑定和服务端配置提供，与 arguments 内同名字段无关。
 */
public final class GovernanceArguments {

    private GovernanceArguments() {}

    /**
     * 浅拷贝 arguments，保留嵌套对象、数组和 {@code null}；不修改入参 Map。
     * {@code null} 入参视为空对象。
     */
    public static Map<String, Object> copy(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(arguments);
    }
}
