package com.beyondzy.demo.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beyondzy.demo.auth.cookie.AuthCookieSupport;
import com.beyondzy.demo.auth.security.SessionTokenAuthenticationManager;
import com.beyondzy.demo.auth.service.AuthSessionService;
import com.beyondzy.demo.auth.support.SubmitCredentialCapture;
import com.beyondzy.demo.identity.SessionIntrospectionService;
import com.beyondzy.demo.tools.DocSearchTools;
import com.beyondzy.demo.user.UserService;
import com.beyondzy.demo.tools.NoteTools;
import com.beyondzy.demo.tools.OrderTools;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/** Run 作用域用户凭证：并发不串用；callAsync 绑定后清理；GovernanceClient 不缓存用户 Token；凭证不落盘。 */
class RunCredentialIsolationTest {

    @AfterEach
    void clearScopes() {
        RunCredentialRegistry.clearAll();
        OutboundUserCredential.clear();
    }

    @Test
    void concurrentRunsDoNotCrossUserTokens() throws Exception {
        ConcurrentHashMap<String, String> seen = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/invoke", exchange -> {
            String userToken = exchange.getRequestHeaders().getFirst("X-Trusted-User-Credential");
            String agent = exchange.getRequestHeaders().getFirst("X-Agent-Token");
            String type = exchange.getRequestHeaders().getFirst("X-Trusted-User-Credential-Type");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String marker = body.contains("user-a") ? "a" : "b";
            seen.put(marker, userToken + "|" + type + "|" + agent);
            byte[] response = """
                    {"traceId":"t","callId":"c","outcome":"completed","code":"OK","message":"ok",
                     "agentMayRetry":false,"rebindRequired":false,"result":{"ok":true},"extensions":{}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            GovernanceClient client = new GovernanceClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "agent-token",
                    Duration.ofSeconds(2));
            RunCredentialRegistry.bindCommand("cmd-a", "1", "threadA", "token-user-a");
            RunCredentialRegistry.bindCommand("cmd-b", "2", "threadB", "token-user-b");
            assertTrue(RunCredentialRegistry.transferCommandToRun("cmd-a", "1", "threadA", "runA"));
            assertTrue(RunCredentialRegistry.transferCommandToRun("cmd-b", "2", "threadB", "runB"));

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            pool.execute(() -> {
                await(start);
                try {
                    invokeBound(client, "1", "threadA", "runA", Map.of("who", "user-a"));
                } finally {
                    done.countDown();
                }
            });
            pool.execute(() -> {
                await(start);
                try {
                    invokeBound(client, "2", "threadB", "runB", Map.of("who", "user-b"));
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            pool.shutdownNow();

            assertEquals("token-user-a|opaque_session|agent-token", seen.get("a"));
            assertEquals("token-user-b|opaque_session|agent-token", seen.get("b"));
            assertNull(OutboundUserCredential.current().orElse(null));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void snapshotOmitsTrustedUserHeadersWhileInvokeRequiresThem() throws Exception {
        AtomicReference<String> snapshotUser = new AtomicReference<>("unset");
        AtomicReference<String> invokeUser = new AtomicReference<>("unset");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/snapshot/current", exchange -> {
            snapshotUser.set(exchange.getRequestHeaders().getFirst("X-Trusted-User-Credential"));
            byte[] body = """
                    {"tenantId":"t","appId":"a","subject":"s","snapshotId":"snap",
                     "tools":[]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/agent/invoke", exchange -> {
            invokeUser.set(exchange.getRequestHeaders().getFirst("X-Trusted-User-Credential"));
            byte[] body = """
                    {"traceId":"t","callId":"c","outcome":"completed","code":"OK","message":"ok",
                     "agentMayRetry":false,"rebindRequired":false,"result":{},"extensions":{}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            GovernanceClient client = new GovernanceClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "agent-token",
                    Duration.ofSeconds(2));
            client.currentSnapshot();
            assertNull(snapshotUser.get());

            OutboundUserCredential.call("captured-at-submit", () -> {
                client.invoke("driver://x", "snap", Map.of("name", "n"));
                return null;
            });
            assertEquals("captured-at-submit", invokeUser.get());
            assertNull(OutboundUserCredential.current().orElse(null));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invokeWithoutBoundCredentialFailsClosedAndDoesNotPretendSuccess() {
        GovernanceClient client = new GovernanceClient("http://127.0.0.1:9", "agent-token", Duration.ofMillis(200));
        GovernanceInvokeResult result = client.invoke("driver://x", "snap", Map.of("name", "n"));
        assertEquals(GovernanceInvokeResult.Kind.TRANSPORT, result.kind());
        assertEquals("TRANSPORT_FAILURE", result.code());
        assertTrue(result.message().contains("凭证") || result.message().contains("治理"));
        assertFalse(result.agentMayRetry());
        assertNull(result.result());
    }

    @Test
    void governanceClientHasNoInstanceFieldForUserToken() {
        for (Field field : GovernanceClient.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String name = field.getName().toLowerCase();
            assertFalse(
                    name.contains("user") && (name.contains("token") || name.contains("credential") || name.contains("session")),
                    "禁止实例字段缓存用户凭证: " + field.getName());
            assertFalse(name.equals("rawtoken") || name.equals("rawsessiontoken") || name.equals("trusteduser"));
        }
    }

    @Test
    void sameAgentPathKeepsConcurrentPhonesIsolatedViaBoundTool() throws Exception {
        // 模拟治理侧：收到用户会话头后按 token→phone 映射（本仓内省语义），断言并发不串号。
        Map<String, String> tokenToPhone = Map.of(
                "opaque-a-13800001111", "13800001111",
                "opaque-b-13900002222", "13900002222");
        ConcurrentHashMap<String, String> invokePhones = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/invoke", exchange -> {
            String userToken = exchange.getRequestHeaders().getFirst("X-Trusted-User-Credential");
            String type = exchange.getRequestHeaders().getFirst("X-Trusted-User-Credential-Type");
            assertEquals("opaque_session", type);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String marker = body.contains("\"who\":\"a\"") ? "a" : "b";
            invokePhones.put(marker, tokenToPhone.get(userToken));
            byte[] response = """
                    {"traceId":"t","callId":"c","outcome":"completed","code":"OK","message":"ok",
                     "agentMayRetry":false,"rebindRequired":false,"result":{"ok":true},"extensions":{}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            GovernanceClient client = new GovernanceClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "agent-token",
                    Duration.ofSeconds(2));
            GovernanceSnapshot snapshot = new GovernanceSnapshot(
                    "tenant-a", "default", "agent-invoke-demo", "snap_shared",
                    List.of(new GovernanceToolView(
                            "driver://mcp/tenant-a/demo/identity.resolve",
                            "identity.resolve",
                            "只读身份查询",
                            Map.of("type", "object", "properties", Map.of("who", Map.of("type", "string"))),
                            "1:1:identity.resolve@1",
                            "1",
                            "1",
                            "read")));
            Toolkit toolkit = new Toolkit();
            new GovernanceToolMount(toolkit, client).install(snapshot);

            RunCredentialRegistry.bindRun("1", "shared-agent-thread", "run-a", "opaque-a-13800001111");
            RunCredentialRegistry.bindRun("2", "shared-agent-thread", "run-b", "opaque-b-13900002222");

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            pool.execute(() -> {
                await(start);
                try {
                    callIdentityTool(toolkit, "1", "shared-agent-thread", "run-a", "a");
                } finally {
                    done.countDown();
                }
            });
            pool.execute(() -> {
                await(start);
                try {
                    callIdentityTool(toolkit, "2", "shared-agent-thread", "run-b", "b");
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            pool.shutdownNow();

            assertEquals("13800001111", invokePhones.get("a"));
            assertEquals("13900002222", invokePhones.get("b"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runHeldTokenDoesNotBypassSessionInvalidationSemantics() {
        // Run 作用域只持有 rawToken，不缓存已验证用户；注销后内省仍 401。
        String held = "still-in-run-registry-after-logout";
        RunCredentialRegistry.bindRun("9", "thread-x", "run-x", held);
        assertEquals(held, RunCredentialRegistry.resolve(RuntimeContext.builder()
                        .userId("9")
                        .sessionId("thread-x")
                        .runId("run-x")
                        .build())
                .orElseThrow());
        assertTrue(OutboundUserCredential.current().isEmpty());

        AuthSessionService sessions = mock(AuthSessionService.class);
        UserService users = mock(UserService.class);
        when(sessions.validateReadOnly(held)).thenReturn(null);
        SessionIntrospectionService introspection = new SessionIntrospectionService(
                new SessionTokenAuthenticationManager(sessions, users, mock(AuthCookieSupport.class)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> introspection.introspect(held));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void missingRunBindingDoesNotBorrowSessionCredential() {
        RunCredentialRegistry.bindCommand("cmd-old", "u1", "chat-1", "token-session-old");
        RunCredentialRegistry.bindRun("u1", "chat-1", "run-old", "token-run-old");
        // 同聊天会话再次“登录”会刷新 command；不得让缺失 Run 绑定借会话级 Token。
        RunCredentialRegistry.bindCommand("cmd-new", "u1", "chat-1", "token-session-new");

        assertTrue(RunCredentialRegistry.resolve(RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("chat-1")
                        .runId("run-missing")
                        .build())
                .isEmpty());
        assertEquals(
                "token-run-old",
                RunCredentialRegistry.resolve(RuntimeContext.builder()
                                .userId("u1")
                                .sessionId("chat-1")
                                .runId("run-old")
                                .build())
                        .orElseThrow());
    }

    @Test
    void sameChatSequentialLoginsKeepDistinctRunBindings() {
        RunCredentialRegistry.bindRun("u1", "chat-seq", "run-a", "token-user-a");
        RunCredentialRegistry.clearRun("u1", "chat-seq", "run-a");
        RunCredentialRegistry.bindRun("u1", "chat-seq", "run-b", "token-user-b");

        assertTrue(RunCredentialRegistry.resolve(RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("chat-seq")
                        .runId("run-a")
                        .build())
                .isEmpty());
        assertEquals(
                "token-user-b",
                RunCredentialRegistry.resolve(RuntimeContext.builder()
                                .userId("u1")
                                .sessionId("chat-seq")
                                .runId("run-b")
                                .build())
                        .orElseThrow());
    }

    @Test
    void transferCommandToRunIsRequiredForAsyncHandoff() {
        RunCredentialRegistry.bindCommand("cmd-async", "u2", "chat-2", "token-async");
        assertTrue(RunCredentialRegistry.resolve(RuntimeContext.builder()
                        .userId("u2")
                        .sessionId("chat-2")
                        .runId("run-async")
                        .build())
                .isEmpty());

        assertTrue(RunCredentialRegistry.transferCommandToRun("cmd-async", "u2", "chat-2", "run-async"));
        assertTrue(RunCredentialRegistry.findByCommand("cmd-async", "u2", "chat-2").isEmpty());
        assertEquals(
                "token-async",
                RunCredentialRegistry.resolve(RuntimeContext.builder()
                                .userId("u2")
                                .sessionId("chat-2")
                                .runId("run-async")
                                .build())
                        .orElseThrow());
        assertFalse(RunCredentialRegistry.transferCommandToRun("cmd-async", "u2", "chat-2", "run-async-2"));
    }

    @Test
    void sameCommandIdAcrossUsersAndSessionsStayIsolated() throws Exception {
        String sharedCommandId = "shared-cmd-id";
        RunCredentialRegistry.bindCommand(sharedCommandId, "user-a", "session-a", "tokenA");
        RunCredentialRegistry.bindCommand(sharedCommandId, "user-b", "session-b", "tokenB");
        RunCredentialRegistry.bindCommand(sharedCommandId, "user-a", "session-a2", "tokenA2");

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        ConcurrentHashMap<String, String> resolved = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        pool.execute(() -> {
            await(start);
            try {
                assertTrue(RunCredentialRegistry.transferCommandToRun(
                        sharedCommandId, "user-a", "session-a", "run-a"));
                resolved.put(
                        "a",
                        RunCredentialRegistry.resolve(RuntimeContext.builder()
                                        .userId("user-a")
                                        .sessionId("session-a")
                                        .runId("run-a")
                                        .build())
                                .orElse("missing"));
            } finally {
                done.countDown();
            }
        });
        pool.execute(() -> {
            await(start);
            try {
                assertTrue(RunCredentialRegistry.transferCommandToRun(
                        sharedCommandId, "user-b", "session-b", "run-b"));
                resolved.put(
                        "b",
                        RunCredentialRegistry.resolve(RuntimeContext.builder()
                                        .userId("user-b")
                                        .sessionId("session-b")
                                        .runId("run-b")
                                        .build())
                                .orElse("missing"));
            } finally {
                done.countDown();
            }
        });
        pool.execute(() -> {
            await(start);
            try {
                assertTrue(RunCredentialRegistry.transferCommandToRun(
                        sharedCommandId, "user-a", "session-a2", "run-a2"));
                resolved.put(
                        "a2",
                        RunCredentialRegistry.resolve(RuntimeContext.builder()
                                        .userId("user-a")
                                        .sessionId("session-a2")
                                        .runId("run-a2")
                                        .build())
                                .orElse("missing"));
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals("tokenA", resolved.get("a"));
        assertEquals("tokenB", resolved.get("b"));
        assertEquals("tokenA2", resolved.get("a2"));

        RunCredentialRegistry.clearCommand(sharedCommandId, "user-a", "session-a");
        RunCredentialRegistry.clearRun("user-a", "session-a", "run-a");
        assertTrue(RunCredentialRegistry.resolve(RuntimeContext.builder()
                        .userId("user-a")
                        .sessionId("session-a")
                        .runId("run-a")
                        .build())
                .isEmpty());
        assertEquals(
                "tokenB",
                RunCredentialRegistry.resolve(RuntimeContext.builder()
                                .userId("user-b")
                                .sessionId("session-b")
                                .runId("run-b")
                                .build())
                        .orElseThrow());
        assertEquals(
                "tokenA2",
                RunCredentialRegistry.resolve(RuntimeContext.builder()
                                .userId("user-a")
                                .sessionId("session-a2")
                                .runId("run-a2")
                                .build())
                        .orElseThrow());
    }

    @Test
    void clearRunDoesNotAffectSiblingActiveRuns() {
        RunCredentialRegistry.bindRun("u3", "chat-3", "run-1", "token-1");
        RunCredentialRegistry.bindRun("u3", "chat-3", "run-2", "token-2");
        RunCredentialRegistry.clearRun("u3", "chat-3", "run-1");

        assertTrue(RunCredentialRegistry.resolve(RuntimeContext.builder()
                        .userId("u3")
                        .sessionId("chat-3")
                        .runId("run-1")
                        .build())
                .isEmpty());
        assertEquals(
                "token-2",
                RunCredentialRegistry.resolve(RuntimeContext.builder()
                                .userId("u3")
                                .sessionId("chat-3")
                                .runId("run-2")
                                .build())
                        .orElseThrow());
    }

    @Test
    void processRestartOrResumeWithoutCredentialFailsClosed() {
        RunCredentialRegistry.bindRun("u4", "chat-4", "run-resume", "token-before-restart");
        RunCredentialRegistry.clearAll(); // 模拟进程重启：内存表不恢复持久化 Token

        assertTrue(RunCredentialRegistry.resolve(RuntimeContext.builder()
                        .userId("u4")
                        .sessionId("chat-4")
                        .runId("run-resume")
                        .build())
                .isEmpty());
    }

    @Test
    void concurrentRunsOnSameChatKeepDistinctTokens() throws Exception {
        ConcurrentHashMap<String, String> seen = new ConcurrentHashMap<>();
        RunCredentialRegistry.bindRun("u5", "same-chat", "run-x", "token-x");
        RunCredentialRegistry.bindRun("u5", "same-chat", "run-y", "token-y");

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.execute(() -> {
            await(start);
            try {
                seen.put(
                        "x",
                        RunCredentialRegistry.resolve(RuntimeContext.builder()
                                        .userId("u5")
                                        .sessionId("same-chat")
                                        .runId("run-x")
                                        .build())
                                .orElse("missing"));
            } finally {
                done.countDown();
            }
        });
        pool.execute(() -> {
            await(start);
            try {
                seen.put(
                        "y",
                        RunCredentialRegistry.resolve(RuntimeContext.builder()
                                        .userId("u5")
                                        .sessionId("same-chat")
                                        .runId("run-y")
                                        .build())
                                .orElse("missing"));
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals("token-x", seen.get("x"));
        assertEquals("token-y", seen.get("y"));
    }

    @Test
    void invokeDoesNotLogRawSessionToken() {
        String secret = "LOG_LEAK_PROBE_TOKEN_" + UUID.randomUUID().toString().replace("-", "");
        Logger clientLogger = (Logger) LoggerFactory.getLogger(GovernanceClient.class);
        Logger toolLogger = (Logger) LoggerFactory.getLogger(GovernanceBoundTool.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        clientLogger.addAppender(appender);
        toolLogger.addAppender(appender);
        try {
            GovernanceClient client =
                    new GovernanceClient("http://127.0.0.1:9", "agent-token", Duration.ofMillis(200));
            OutboundUserCredential.call(secret, () -> {
                client.invoke("driver://x", "snap", Map.of("name", "n"));
                return null;
            });
            // 缺凭证路径也会打 warn；确保任意日志都不含 raw token。
            client.invoke("driver://x", "snap", Map.of("name", "n"));
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains(secret), event.getFormattedMessage());
            }
        } finally {
            clientLogger.detachAppender(appender);
            toolLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void runScopedCredentialNeverPersistsToSessionLogOrWorkspace(@TempDir Path workspace) throws Exception {
        String secret = "RAW_SESSION_TOKEN_MUST_NOT_PERSIST_" + UUID.randomUUID().toString().replace("-", "");
        RecordingGateway gateway = new RecordingGateway();
        gateway.response = GovernanceInvokeResult.completed(
                "OK",
                "调用成功",
                Map.of(
                        "content",
                        List.of(Map.of("type", "text", "text", "{\"resolutionStatus\":\"RESOLVED\"}")),
                        "isError",
                        false));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        toolkit.registerTool(new DocSearchTools());
        toolkit.registerTool(new NoteTools(workspace));
        GovernanceToolMount mount = new GovernanceToolMount(toolkit, gateway);
        mount.install(new GovernanceSnapshot(
                "tenant-a",
                "default",
                "agent-invoke-demo",
                "snap_cred",
                List.of(new GovernanceToolView(
                        "driver://mcp/tenant-a/cms-identity-invoke/identity.resolve",
                        "identity.resolve",
                        "只读身份查询",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("name", Map.of("type", "string")),
                                "required",
                                List.of("name"),
                                "additionalProperties",
                                false),
                        "1:1:identity.resolve@1",
                        "9",
                        "3",
                        "read"))));

        ScriptModel model = new ScriptModel();
        RuntimeContext context = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("thread-cred")
                .build();
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("Session Chat")
                .agentId("session-chat")
                .model(model)
                .sysPrompt(com.beyondzy.demo.session.ChatSessions.systemPrompt(true))
                .workspace(workspace)
                .sessionLogStore(new WorkspaceSessionLogStore(new LocalFilesystem(workspace)))
                .toolkit(toolkit)
                .permissionContext(PermissionContextState.builder()
                        .addAllowRule(
                                "lookup_order",
                                new PermissionRule("lookup_order", null, PermissionBehavior.ALLOW, "平台查询策略"))
                        .build())
                .disableShellTool()
                .disableFilesystemTools()
                .disableWebTools()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableToolsConfig()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .maxIters(4)
                .build()) {
            AgentSession session = agent.session(context);
            session.start();
            RunCredentialRegistry.bindCommand("req-cred", "user-1", "thread-cred", secret);
            AgentSession.Task submitted = session.submit("req-cred", "帮我查询田沐阳的信息。");
            RunCredentialLifecycle.arm(session, "user-1", "thread-cred", submitted);
            AgentSession.Task done = SubmitCredentialCapture.call(secret, () ->
                    session.await(submitted).block(Duration.ofSeconds(20)));
            assertTrue(done != null && done.settled(), done == null ? "null" : done.status());
            assertEquals(1, gateway.calls.size());
            // 终态清理由 Lifecycle 后台完成；稍等监视器收口。
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                AgentSession.Task finished = session.task("req-cred");
                boolean clearedCommand = RunCredentialRegistry.findByCommand("req-cred").isEmpty();
                boolean clearedRun = finished == null
                        || finished.runId() == null
                        || RunCredentialRegistry.resolve(RuntimeContext.builder()
                                        .userId("user-1")
                                        .sessionId("thread-cred")
                                        .runId(finished.runId())
                                        .build())
                                .isEmpty();
                if (clearedCommand && clearedRun) break;
                Thread.sleep(25);
            }
            session.close();
        }

        assertTrue(Files.exists(workspace));
        try (Stream<Path> files = Files.walk(workspace)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                String text = new String(bytes, StandardCharsets.UTF_8);
                assertFalse(text.contains(secret), "SessionLog/Workspace 不得落盘用户会话凭证: " + file);
            }
        }
        assertNull(OutboundUserCredential.current().orElse(null));
        assertTrue(SubmitCredentialCapture.current().isEmpty());
    }

    private static void invokeBound(
            GovernanceClient client, String userId, String sessionId, String runId, Map<String, Object> args) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .runId(runId)
                .build();
        String token = RunCredentialRegistry.resolve(context).orElseThrow();
        OutboundUserCredential.call(token, () -> {
            client.invoke("driver://x", "snap", args);
            return null;
        });
    }

    private static void callIdentityTool(
            Toolkit toolkit, String userId, String sessionId, String runId, String who) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .runId(runId)
                .build();
        // 先按 Run 绑定解析；再用 ThreadLocal 覆盖 Toolkit 可能丢掉 runId 的情况（禁止会话回退）。
        String token = RunCredentialRegistry.resolve(context).orElseThrow();
        ToolResultBlock result = OutboundUserCredential.call(token, () -> toolkit.callTool(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("call-" + who)
                                .name("identity_resolve")
                                .input(Map.of("who", who))
                                .content("{\"who\":\"" + who + "\"}")
                                .build())
                        .input(Map.of("who", who))
                        .runtimeContext(context)
                        .build())
                .block(Duration.ofSeconds(5)));
        assertTrue(result != null && result.getState() != null, "工具调用应返回结果");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingGateway implements GovernanceGateway {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        GovernanceInvokeResult response;

        @Override
        public GovernanceSnapshot currentSnapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments) {
            calls.add(new Call(uri, snapshotId, Map.copyOf(arguments)));
            return response;
        }

        private record Call(String uri, String snapshotId, Map<String, Object> arguments) {}
    }

    private static final class ScriptModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "script-cred";
        }

        @Override
        public Flux<io.agentscope.core.model.ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            boolean sawTool = messages.stream().anyMatch(message ->
                    message.getRole() == MsgRole.TOOL || message.hasContentBlocks(ToolResultBlock.class));
            ContentBlock content;
            String finish;
            if (!sawTool) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("name", "田沐阳");
                content = ToolUseBlock.builder()
                        .id("call-cred-" + calls.get())
                        .name("identity_resolve")
                        .input(args)
                        .content(JsonUtils.getJsonCodec().toJson(args))
                        .build();
                finish = "tool_calls";
            } else {
                content = TextBlock.builder().text("查询完成").build();
                finish = "stop";
            }
            return Flux.just(io.agentscope.core.model.ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(content))
                    .finishReason(finish)
                    .build());
        }
    }
}
