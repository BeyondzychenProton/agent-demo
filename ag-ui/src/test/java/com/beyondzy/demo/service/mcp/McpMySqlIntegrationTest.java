package com.beyondzy.demo.service.mcp;
import static org.junit.jupiter.api.Assertions.*;
import com.beyondzy.demo.exception.mcp.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 仅对显式提供的隔离测试库运行，不读取应用业务库配置。 */
@EnabledIfEnvironmentVariable(named="MCP_TEST_MYSQL_URL",matches=".+")
class McpMySqlIntegrationTest {
    @Test void actualMigrationPersistenceConcurrencyAndGate() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("MCP_TEST_MYSQL_URL"),
                System.getenv("MCP_TEST_MYSQL_USER"),System.getenv("MCP_TEST_MYSQL_PASSWORD"));
        var h=new McpHarness(ds,new ClassPathResource("db/mysql/V14__mcp_management.sql").getContentAsString(StandardCharsets.UTF_8));
        var server=new McpHarness.Server();h.mount(server);long id=h.id(),tid=h.toolId("meeting.list_mine");
        h.guard.check(server.id(),"meeting.list_mine");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->{
                ready.countDown();go.await();
                try { h.service.metadata(id,"并发更新","说明","负责人",0,McpHarness.ADMIN);return true; }
                catch(McpManagementException e) {assertEquals(409,e.status());return false;}
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            int successes=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);
        }
        h.service.toolStatus(id,tid,false,0,McpHarness.ADMIN);
        h.mount(server);
        assertEquals("MCP_TOOL_DISABLED",assertThrows(McpCallException.class,()->h.guard.check(server.id(),"meeting.list_mine")).code());
        server.definitions=List.of(McpHarness.definition("Case"),McpHarness.definition("case"));
        h.service.verify(id,McpHarness.ADMIN);
        assertEquals(2,h.jdbc.queryForObject("SELECT COUNT(*) FROM ai_mcp_tool WHERE present=1",Integer.class));
        assertFalse((Boolean)h.service.toolDetail(id,h.toolId("Case")).get("enabled"));
        h.service.toolStatus(id,h.toolId("Case"),true,0,McpHarness.ADMIN);
        assertFalse((Boolean)h.service.toolDetail(id,h.toolId("case")).get("enabled"));
        assertTrue(h.service.history(id,1,20).toString().contains("测试管理员"));
    }
}
