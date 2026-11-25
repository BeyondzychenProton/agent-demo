package com.beyondzy.demo.service.mcp;

import com.beyondzy.demo.mcp.*;
import java.util.List;
import java.util.Map;

/** 只给启动挂载登记目录，现有 Skill 目录发现不增加管理或关联语义。 */
public final class ManagedMcpServer implements McpServer {
    private final McpServer delegate;
    private final McpManagementService management;
    private final McpRegistry registry;
    private List<McpToolDefinition> startup=List.of();
    public ManagedMcpServer(McpServer delegate,McpManagementService management,McpRegistry registry) {
        this.delegate=delegate; this.management=management; this.registry=registry;
    }
    @Override public String id() { return delegate.id(); }
    @Override public List<McpToolDefinition> listTools() { return delegate.listTools(); }
    @Override public List<McpToolDefinition> toolsForMount() { startup=management.startup(delegate); return startup; }
    @Override public void mounted(List<McpBoundTool> tools) { registry.mounted(id(),startup,tools); }
    @Override public McpCallResult callTool(String name,Map<String,Object> args,String userId) {
        return delegate.callTool(name,args,userId);
    }
}
