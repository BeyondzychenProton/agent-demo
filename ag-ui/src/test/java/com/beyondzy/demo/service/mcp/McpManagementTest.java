package com.beyondzy.demo.service.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.beyondzy.demo.exception.mcp.*;
import com.beyondzy.demo.mcp.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.beyondzy.demo.mcp.mantianxing.MantianxingMcpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class McpManagementTest {
    @Test void manyToolsUseStablePaginationAndFilteredCounts() throws Exception {
        var h=McpHarness.open();var server=new McpHarness.Server();
        server.definitions=java.util.stream.IntStream.range(0,45).mapToObj(i->McpHarness.definition("tool."+i)).toList();
        h.mount(server);long id=h.id();
        var page=h.service.toolPage(id,"","all",2,20);
        assertEquals(45,page.get("total"));assertEquals(20,((List<?>)page.get("items")).size());
        assertEquals(5,((List<?>)h.service.toolPage(id,"","all",3,20).get("items")).size());
        h.service.toolStatus(id,h.toolId("tool.0"),false,0,McpHarness.ADMIN);
        assertEquals(44,h.service.toolPage(id,"","open",1,20).get("total"));
        assertEquals(1,h.service.toolPage(id,"","closed",1,20).get("total"));
    }
    @Test void disablingDuringInitializeBlocksTheActualToolsCall() throws Exception {
        var h=McpHarness.open();var configured=new McpHarness.Server();h.mount(configured);
        AtomicInteger sends=new AtomicInteger();
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/", exchange->{
            var body=new ObjectMapper().readTree(exchange.getRequestBody());
            String method=body.path("method").asText();
            if("notifications/initialized".equals(method)){
                h.service.status(h.id(),false,0,McpHarness.ADMIN);
                exchange.sendResponseHeaders(202,-1);exchange.close();return;
            }
            if("tools/call".equals(method))sends.incrementAndGet();
            String response="{\"jsonrpc\":\"2.0\",\"id\":"+body.path("id").asInt()+",\"result\":{}}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        http.start();
        try {
            var client=new MantianxingMcpClient("http://127.0.0.1:"+http.getAddress().getPort(),
                    "test","test",Duration.ofSeconds(3),"test",u->"test",h.guard);
            // 即使审批此前已获准，最终执行仍重新查库。
            var result=client.callTool("meeting.list_mine",Map.of(),"approved-user");
            assertFalse(result.success());assertEquals("MCP_SERVICE_DISABLED",result.code());assertEquals(0,sends.get());
        } finally {http.stop(0);}
    }
    @Test void persistentSwitchesDoNotResetOnDiscoveryOrRestart() throws Exception {
        var h=McpHarness.open();var server=new McpHarness.Server();h.mount(server);
        long id=h.id(),tid=h.toolId("meeting.list_mine");
        assertTrue((Boolean)h.service.toolDetail(id,tid).get("enabled"));
        h.service.toolStatus(id,tid,false,0,McpHarness.ADMIN);
        h.service.status(id,false,0,McpHarness.ADMIN);
        h.service.status(id,true,1,McpHarness.ADMIN);
        h.mount(server);
        assertFalse((Boolean)h.service.toolDetail(id,tid).get("enabled"));
        assertEquals("MCP_TOOL_DISABLED",assertThrows(McpCallException.class,()->h.guard.check(server.id(),"meeting.list_mine")).code());
        server.definitions=List.of(McpHarness.definition("meeting.list_mine"),McpHarness.definition("room.new"));
        assertTrue((Boolean)h.service.verify(id,McpHarness.ADMIN).get("success"));
        var added=h.service.toolDetail(id,h.toolId("room.new"));
        assertFalse((Boolean)added.get("enabled"));assertFalse((Boolean)added.get("loadedInCurrentInstance"));
        h.mount(server);
        assertFalse((Boolean)h.service.toolDetail(id,h.toolId("room.new")).get("enabled"));
    }
    @Test void failedAndMalformedDiscoveryPreserveSnapshotAndStatus() throws Exception {
        var h=McpHarness.open();var server=new McpHarness.Server();h.mount(server);
        long id=h.id(),tid=h.toolId("meeting.list_mine");
        server.failure=new McpCallException("UNAUTHENTICATED","secret phone must never be exposed");
        var result=h.service.verify(id,McpHarness.ADMIN);
        assertFalse((Boolean)result.get("success"));
        assertFalse(result.toString().contains("secret phone"));
        assertEquals(1,h.service.detail(id).get("discoveredCount"));
        assertTrue((Boolean)h.service.detail(id).get("historicalSnapshot"));
        h.guard.check(server.id(),"meeting.list_mine");
        server.failure=null;server.definitions=List.of(McpHarness.definition("same"),McpHarness.definition("same"));
        assertFalse((Boolean)h.service.verify(id,McpHarness.ADMIN).get("success"));
        assertTrue((Boolean)h.service.toolDetail(id,tid).get("present"));
    }
    @Test void removedToolsBlockButReappearingToolsRetainTheirSwitch() throws Exception {
        var h=McpHarness.open();var server=new McpHarness.Server();h.mount(server);long id=h.id(),tid=h.toolId("meeting.list_mine");
        server.definitions=List.of();h.service.verify(id,McpHarness.ADMIN);
        assertEquals("MCP_TOOL_UNAVAILABLE",assertThrows(McpCallException.class,()->h.guard.check(server.id(),"meeting.list_mine")).code());
        assertTrue((Boolean)h.service.toolDetail(id,tid).get("enabled"));
        server.definitions=List.of(McpHarness.definition("meeting.list_mine"));h.service.verify(id,McpHarness.ADMIN);
        h.guard.check(server.id(),"meeting.list_mine");
    }
    @Test void metadataAndAuditAreTransactionalAndUseCompareAndSwap() throws Exception {
        var h=McpHarness.open();var server=new McpHarness.Server();h.mount(server);long id=h.id();
        h.service.metadata(id,"新名称","说明","负责人",0,McpHarness.ADMIN);
        assertEquals("新名称",h.service.detail(id).get("displayName"));
        assertEquals(409,assertThrows(McpManagementException.class,()->h.service.status(id,false,0,McpHarness.ADMIN)).status());
        assertTrue(h.service.history(id,1,20).toString().contains("测试管理员"));
        h.jdbc.execute("DROP TABLE ai_mcp_operation");
        assertThrows(RuntimeException.class,()->h.service.status(id,false,1,McpHarness.ADMIN));
        assertEquals(1,h.jdbc.queryForObject("SELECT enabled FROM ai_mcp_service WHERE id=?",Integer.class,id));
        assertEquals(1L,h.jdbc.queryForObject("SELECT control_version FROM ai_mcp_service WHERE id=?",Long.class,id));
    }
    @Test void latestGateSeesAnotherInstanceWriteAndFailsClosedOnStorageFailure() throws Exception {
        var h=McpHarness.open();var server=new McpHarness.Server();h.mount(server);long id=h.id();
        var other=new McpInvocationGuard(h.services,h.registry,h.manager);
        other.check(server.id(),"meeting.list_mine");
        h.service.status(id,false,0,McpHarness.ADMIN);
        assertEquals("MCP_SERVICE_DISABLED",assertThrows(McpCallException.class,()->other.check(server.id(),"meeting.list_mine")).code());
        h.jdbc.execute("DROP TABLE ai_mcp_tool");
        assertEquals("MCP_STATE_UNAVAILABLE",assertThrows(McpCallException.class,()->other.check(server.id(),"meeting.list_mine")).code());
    }
    @Test void schemaChangesRemainPendingUntilMountedAndSecretsAreNotDisplayed() throws Exception {
        var h=McpHarness.open();var server=new McpHarness.Server();h.mount(server);long id=h.id(),tid=h.toolId("meeting.list_mine");
        server.definitions=List.of(new McpToolDefinition("meeting.list_mine","新说明",Map.of("type","object","required",List.of("q")),false));
        h.service.verify(id,McpHarness.ADMIN);
        assertTrue((Boolean)h.service.toolDetail(id,tid).get("definitionChanged"));
        h.mount(server);assertFalse((Boolean)h.service.toolDetail(id,tid).get("definitionChanged"));
        assertFalse(h.service.detail(id).toString().contains("token=hidden"));
    }
    @Test void olderVerificationCannotOverwriteNewerSnapshot() throws Exception {
        var h=McpHarness.open();var initial=new McpHarness.Server();h.mount(initial);long id=h.id();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger attempts=new AtomicInteger();
        var server=new McpHarness.Server(){
            @Override public List<McpToolDefinition> listTools() {
                if(attempts.getAndIncrement()==0) {
                    entered.countDown();
                    try { if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException(); } catch(InterruptedException e){throw new RuntimeException(e);}
                    return List.of(McpHarness.definition("old"));
                }
                return List.of(McpHarness.definition("new"));
            }
        };
        h.register(server);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var old=pool.submit(()->h.service.verify(id,McpHarness.ADMIN));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTrue((Boolean)h.service.verify(id,McpHarness.ADMIN).get("success"));
            release.countDown();
            assertTrue((Boolean)old.get(5,TimeUnit.SECONDS).get("superseded"));
        } finally { release.countDown(); }
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM ai_mcp_tool WHERE tool_key='new' AND present=1",Integer.class));
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM ai_mcp_tool WHERE tool_key='old'",Integer.class));
    }
    @Test void verificationBeforeFirstMountDoesNotAutoOpenAndPagesAreValidated() throws Exception {
        var h=McpHarness.open();var server=new McpHarness.Server();h.register(server);h.service.ensureConfigured();long id=h.id();
        h.service.verify(id,McpHarness.ADMIN);h.mount(server);
        assertFalse((Boolean)h.service.toolDetail(id,h.toolId("meeting.list_mine")).get("enabled"));
        assertEquals(400,assertThrows(McpManagementException.class,()->h.service.toolPage(id,"","all",0,20)).status());
        assertEquals(400,assertThrows(McpManagementException.class,()->h.service.list("","bad",null,null,1,20)).status());
    }
}
