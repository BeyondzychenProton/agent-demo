package com.beyondzy.demo.mcp;

import java.util.List;
import java.util.Map;

/**
 * 一个可挂载的 MCP 服务。
 *
 * <p>运行时只依赖这个接口：发现工具、用当前登录用户调用工具。
 * 鉴权方式、身份字段留在各自的实现里。新增一家 MCP 时实现本接口并在装配处注册。
 */
public interface McpServer {

    /** 稳定标识，用于区分多家服务上的同名工具。 */
    String id();

    /** 启动时发现工具。失败抛出运行时异常，由装配方决定是否跳过这一家。 */
    List<McpToolDefinition> listTools();

    /** 启动挂载可登记同一份发现快照，普通目录查询仍使用 listTools。 */
    default List<McpToolDefinition> toolsForMount() { return listTools(); }

    default void mounted(List<McpBoundTool> tools) {}

    /**
     * 以当前登录用户调用工具。
     * userId 可能为空；实现自行决定如何换成自己的身份，不能从 arguments 里推断调用者。
     */
    McpCallResult callTool(String toolName, Map<String, Object> arguments, String userId);
}
