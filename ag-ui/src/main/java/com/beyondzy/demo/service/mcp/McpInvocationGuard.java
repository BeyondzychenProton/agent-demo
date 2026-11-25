package com.beyondzy.demo.service.mcp;

import com.beyondzy.demo.exception.mcp.McpCallException;
import com.beyondzy.demo.mapper.mcp.McpServiceMapper;
import com.beyondzy.demo.mcp.McpInvocationCheck;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class McpInvocationGuard implements McpInvocationCheck {
    private final McpServiceMapper mapper;
    private final McpRegistry registry;
    private final TransactionTemplate read;
    public McpInvocationGuard(McpServiceMapper mapper, McpRegistry registry, PlatformTransactionManager tx) {
        this.mapper=mapper; this.registry=registry;
        read=new TransactionTemplate(tx);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read.setReadOnly(true);
    }
    @Override public void check(String provider, String tool) {
        McpServiceMapper.InvocationState state;
        try { state=read.execute(s->mapper.invocationState(provider,tool)); }
        catch (RuntimeException e) { throw new McpCallException("MCP_STATE_UNAVAILABLE","暂时无法读取 MCP 管理状态，本次未执行"); }
        if (state==null || !Integer.valueOf(1).equals(state.present))
            throw new McpCallException("MCP_TOOL_UNAVAILABLE","该工具不在当前有效目录中，本次未执行");
        if (!Integer.valueOf(1).equals(state.serviceEnabled))
            throw new McpCallException("MCP_SERVICE_DISABLED","该 MCP 服务已停用，本次未执行");
        if (!Integer.valueOf(1).equals(state.toolEnabled))
            throw new McpCallException("MCP_TOOL_DISABLED","该工具已关闭，本次未执行");
        if (registry.loaded(provider,tool)==null)
            throw new McpCallException("MCP_TOOL_NOT_LOADED","当前进程尚未加载该工具，请管理员完成重启");
    }
}
