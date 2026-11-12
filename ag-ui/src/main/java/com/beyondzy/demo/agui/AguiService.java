package com.beyondzy.demo.agui;

import java.util.Map;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * AG-UI 薄适配层：把本 demo 的 HarnessAgent + SessionLog 主干，翻译成 CopilotKit 能消费的
 * AG-UI 标准事件流与线程协议。
 *
 * <p>设计口径（对应改写后的大纲 5.2.2 / 5.4）：
 * <ul>
 *   <li>会话事实留在 SessionLog，列表摘要从执行日志与 Inbox 派生；
 *   <li>执行仍由 {@link ChatSessions} 提交，本类只做“事件翻译 + 协议外壳”；
 *   <li>聊天与工具事件沿 AG-UI 输出；审批操作走独立服务端视图，本层不声明 CopilotKit 原生 HITL 能力。
 * </ul>
 *
 * <p>注意三个编号：客户端 runId 标识协议流；commandId 在命令接收时已存在；
 * 实际 executionRunId 由框架稍后启动执行时生成。排队回执没有实际执行编号是正常状态，
 * 不能用它作为非空映射值，也不能令投影在编号为空时放弃执行筛选。
 */
public interface AguiService {
    Map<String, Object> info();

    Map<String, Object> listThreads(int limit, String cursor);

    Map<String, Object> createThread(Map<String, Object> request);

    Map<String, Object> noopThread(String threadId, Map<String, Object> request);

    Map<String, Object> renameThread(String threadId, Map<String, Object> request);

    Map<String, Object> messages(String threadId);

    Flux<ServerSentEvent<String>> connect(String threadId, String runId);

    Flux<ServerSentEvent<String>> connect(String userId, String threadId, String runId);

    Flux<ServerSentEvent<String>> run(String agentId, Map<String, Object> body);

    Flux<ServerSentEvent<String>> run(String userId, String agentId, Map<String, Object> body);
}
