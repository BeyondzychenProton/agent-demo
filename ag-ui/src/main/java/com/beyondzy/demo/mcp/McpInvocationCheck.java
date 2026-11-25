package com.beyondzy.demo.mcp;
/** 每次实际发送业务调用前执行，不缓存审批或数据库判断。 */
@FunctionalInterface
public interface McpInvocationCheck {
    void check(String provider, String tool);
}
