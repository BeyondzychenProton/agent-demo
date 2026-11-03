package com.beyondzy.demo.web.agui;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CopilotKit / AG-UI 协议面（薄外壳）。
 *
 * <p>只实现最小聊天所需端点：info 探测、线程列表/创建、历史消息、connect 握手、run 流式。
 * 线程的重命名/归档/删除仍只回显成功；真正的数据权威是 SessionLog。
 * 原有 /api 最小闭环接口保持不变，两套接口并存。
 */
@RestController
@RequestMapping("/agui/run")
public final class AguiController {

    private final AguiService agui;

    public AguiController(AguiService agui) {
        this.agui = agui;
    }

    /** GET /agui/run/info：Runtime 能力与 agent 声明。 */
    @GetMapping("/info")
    public Map<String, Object> info() {
        return agui.info();
    }

    /** GET /agui/run/threads：按摘要更新时间分页列出会话。 */
    @GetMapping("/threads")
    public Map<String, Object> threads(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须在 1~100 之间");
        try {
            return agui.listThreads(limit, cursor);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    /** POST /agui/run/threads：新建线程（只发 id，惰性落盘）。 */
    @PostMapping("/threads")
    public Map<String, Object> createThread(@RequestBody(required = false) Map<String, Object> body) {
        return agui.createThread(body == null ? Map.of() : body);
    }

    /** PATCH /agui/run/threads/{id}：重命名（不落表，回显）。 */
    @PatchMapping("/threads/{id}")
    public Map<String, Object> renameThread(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return agui.noopThread(id, body == null ? Map.of() : body);
    }

    /** POST /agui/run/threads/{id}/archive：归档（不落表，回显成功）。 */
    @PostMapping("/threads/{id}/archive")
    public Map<String, Object> archiveThread(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return agui.noopThread(id, body == null ? Map.of() : body);
    }

    /** DELETE /agui/run/threads/{id}：删除（不删日志，仅回显成功）。 */
    @DeleteMapping("/threads/{id}")
    public Map<String, Object> deleteThread(@PathVariable String id) {
        return Map.of("deleted", true, "threadId", id);
    }

    /** GET /agui/run/threads/{id}/messages：历史消息。 */
    @GetMapping("/threads/{id}/messages")
    public Map<String, Object> messages(@PathVariable String id) {
        return agui.messages(id);
    }

    /** POST /agui/agent/{agentId}/connect（SSE）：连接握手 + 历史快照回放。 */
    @PostMapping(
            value = "/agent/{agentId}/connect",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> connect(
            @PathVariable String agentId, @RequestBody(required = false) Map<String, Object> body) {
        String threadId = str(body == null ? null : body.get("threadId"));
        String runId = str(body == null ? null : body.get("runId"));
        return agui.connect(threadId, runId);
    }

    /** POST /agui/agent/{agentId}/run（SSE）：提交消息并返回 AG-UI 流式事件。 */
    @PostMapping(
            value = "/agent/{agentId}/run",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> run(
            @PathVariable String agentId, @RequestBody Map<String, Object> body) {
        return agui.run(agentId, body);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
