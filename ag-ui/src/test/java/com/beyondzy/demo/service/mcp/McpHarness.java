package com.beyondzy.demo.service.mcp;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.beyondzy.demo.config.mybatis.MybatisPages;
import com.beyondzy.demo.config.mybatis.MybatisPlusMetaObjectHandler;
import com.beyondzy.demo.enums.mcp.McpProvider;
import com.beyondzy.demo.mapper.mcp.*;
import com.beyondzy.demo.mcp.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

final class McpHarness {
    final JdbcTemplate jdbc;
    final McpRegistry registry=new McpRegistry();
    final McpManagementService service;
    final McpInvocationGuard guard;
    final McpServiceMapper services;
    final McpToolMapper tools;
    final DataSourceTransactionManager manager;
    static final McpManagementService.Actor ADMIN=new McpManagementService.Actor(1L,"测试管理员");
    static McpHarness open() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mcp_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        String ddl=new ClassPathResource("db/mysql/V14__mcp_management.sql").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin","")
                .replaceAll("ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci","");
        return new McpHarness(ds,ddl);
    }
    McpHarness(DataSource ds,String ddl) {
        jdbc=new JdbcTemplate(ds);
        for(String statement:ddl.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        MybatisConfiguration config=new MybatisConfiguration(new Environment("mcp",new SpringManagedTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        GlobalConfig global=new GlobalConfig();
        global.setBanner(false); global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config,global);
        config.addInterceptor(MybatisPages.interceptor());
        config.addMapper(McpServiceMapper.class); config.addMapper(McpToolMapper.class); config.addMapper(McpOperationMapper.class);
        SqlSessionFactory factory=new MybatisSqlSessionFactoryBuilder().build(config);
        var template=new SqlSessionTemplate(factory);
        services=template.getMapper(McpServiceMapper.class); tools=template.getMapper(McpToolMapper.class);
        manager=new DataSourceTransactionManager(ds);
        McpManagementService raw=new McpManagementService(services,tools,template.getMapper(McpOperationMapper.class),registry,factory);
        service=transactional(raw,manager);
        raw.bindSelf(service);
        guard=new McpInvocationGuard(services,registry,manager);
    }
    private static McpManagementService transactional(McpManagementService target, DataSourceTransactionManager manager) {
        TransactionInterceptor interceptor=new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        interceptor.afterPropertiesSet();
        ProxyFactory proxy=new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);
        return (McpManagementService)proxy.getProxy();
    }
    void register(McpServer server) {
        registry.register(server,new McpRegistry.Descriptor("会议服务","http://localhost/mcp?token=hidden","Streamable HTTP","应用签名","测试"));
    }
    long id() { return jdbc.queryForObject("SELECT id FROM ai_mcp_service",Long.class); }
    long toolId(String key) { return jdbc.queryForObject("SELECT id FROM ai_mcp_tool WHERE tool_key=?",Long.class,key); }
    long version(long id) { return ((Number)service.detail(id).get("controlVersion")).longValue(); }
    long toolVersion(long id,long toolId) { return ((Number)service.toolDetail(id,toolId).get("controlVersion")).longValue(); }
    static McpToolDefinition definition(String name) { return new McpToolDefinition(name,"说明",Map.of("type","object"),false); }
    static class Server implements McpServer {
        volatile List<McpToolDefinition> definitions=List.of(definition("meeting.list_mine"));
        volatile RuntimeException failure;
        @Override public String id(){return McpProvider.Code.MANTIANXING;}
        @Override public List<McpToolDefinition> listTools(){if(failure!=null)throw failure;return definitions;}
        @Override public McpCallResult callTool(String name,Map<String,Object> args,String user){return McpCallResult.success(Map.of());}
    }
    void mount(Server server) {
        register(server);
        McpToolMount.install(new io.agentscope.core.tool.Toolkit(),new ManagedMcpServer(server,service,registry));
    }
}
