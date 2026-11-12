package com.beyondzy.demo.web.chat;

import io.agentscope.core.util.JsonUtils;
import com.beyondzy.demo.handler.admin.AuthRequests;
import com.beyondzy.demo.handler.admin.CurrentUser;
import com.beyondzy.demo.exception.session.SessionNotFoundException;
import com.beyondzy.demo.contract.UserContext;
import com.beyondzy.demo.contract.ThreadView;
import com.beyondzy.demo.session.ChatSessions;
import com.beyondzy.demo.readmodel.ChatHistory;
import com.beyondzy.demo.readmodel.IncrementalEventProjection;
import com.beyondzy.demo.web.chat.req.ApprovalAnswerRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 最小后端 API：列表 / 快照 / 提交 / SSE 增量。
 *
 * <p>不提供前端页面，用 curl 或任意 HTTP 客户端验证闭环。
 */
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {
    private final ChatSessions sessions;

    public ChatController(ChatSessions sessions) {
        this.sessions = sessions;
    }

    /** 在当前登录用户上下文中执行同步读取，避免阻塞 WebFlux 事件循环。 */
    private Mono<String> json(Supplier<?> read) {
        return AuthRequests.call(() -> JsonUtils.getJsonCodec().toJson(read.get()));
    }

    /**
     * GET /api/sessions：已落盘会话列表 + 当前模型名。
     */
    @GetMapping("/sessions")
    public Mono<String> list() {
        return json(() -> Map.of("sessions", sessions.list(), "model", sessions.modelName()));
    }

    /**
     * GET /api/sessions/{id}：现算快照（消息 + 卡片 + turn + pending + cursor）。
     */
    @GetMapping("/sessions/{id}")
    public Mono<String> snapshot(@PathVariable String id) {
        return json(() -> sessions.snapshot(id));
    }

    /**
     * POST /api/sessions/{id}/turns：提交一次任务，body={request_id, message}。
     */
    @PostMapping("/sessions/{id}/turns")
    public Mono<String> submit(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        return json(() -> sessions.submit(id, body.get("request_id"), body.get("message")));
    }

    /**
     * GET /api/sessions/{id}/stream：SSE 增量通道（大纲 5.4.2 第 2 步）。
     *
     * <p>after 参数与 Last-Event-ID 头取较大水位；300ms 轮询 committed 批次逐帧推送，
     * 每帧带 id=水位；15s keepalive 注释帧防止代理断开空连接。
     */
    @GetMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return CurrentUser.requireUserId().flatMapMany(userId -> {
            String uid = String.valueOf(userId);
            return Mono.fromCallable(() -> UserContext.call(uid, () -> {
                                long position = Math.max(
                                        ChatHistory.position(id, after),
                                        ChatHistory.position(id, lastEventId));
                                sessions.events(id, position, 1);
                                return new AtomicLong(position);
                            }))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(cursor -> {
                        Flux<ServerSentEvent<String>> committed =
                                Flux.interval(Duration.ZERO, Duration.ofMillis(300))
                                        .onBackpressureDrop()
                                        .concatMap(tick -> Mono.fromCallable(() ->
                                                        UserContext.call(uid, () ->
                                                                sessions.events(id, cursor.get(), 128)))
                                                .subscribeOn(Schedulers.boundedElastic()), 1)
                                        .flatMapIterable(batch -> batch)
                                        .map(event -> {
                                            cursor.set(event.seq());
                                            return ServerSentEvent.<String>builder(
                                                            JsonUtils.getJsonCodec()
                                                                    .toJson(ChatHistory.frame(event)))
                                                    .event("committed")
                                                    .id(ChatHistory.cursor(id, event.seq()))
                                                    .build();
                                        });
                        Flux<ServerSentEvent<String>> heartbeat =
                                Flux.interval(Duration.ofSeconds(15))
                                        .map(tick -> ServerSentEvent.<String>builder()
                                                .comment("keepalive").build());
                        return Flux.merge(committed, heartbeat)
                                .startWith(ServerSentEvent.<String>builder().comment("connected").build());
                    });
        });
    }

    /**
     * GET /api/threads/{id}/view：6.1.8 SnapshotResponse（页面视图 + 待答 + 水位）。
     */
    @GetMapping("/threads/{id}/view")
    public Mono<String> view(@PathVariable String id) {
        return json(() -> sessions.view(id));
    }

    /**
     * GET /api/threads/{id}/view/events：增量通道（committed 批次 → IncrementalEvent）。
     *
     * <p>与 /stream 共用水位规则：after 与 Last-Event-ID 取较大者，300ms 轮询，15s 心跳。
     */
    @GetMapping(
            value = "/threads/{id}/view/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> viewEvents(
            @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return CurrentUser.requireUserId().flatMapMany(userId -> {
            String uid = String.valueOf(userId);
            return Mono.fromCallable(() -> UserContext.call(uid, () -> {
                                long position = Math.max(
                                        ChatHistory.position(id, after),
                                        ChatHistory.position(id, lastEventId));
                                sessions.events(id, position, 1);
                                return new AtomicLong(position);
                            }))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(cursor -> {
                        IncrementalEventProjection projection = new IncrementalEventProjection();
                        Flux<ServerSentEvent<String>> committed =
                                Flux.interval(Duration.ZERO, Duration.ofMillis(300))
                                        .onBackpressureDrop()
                                        .concatMap(tick -> Mono.fromCallable(() ->
                                                        UserContext.call(uid, () ->
                                                                sessions.events(id, cursor.get(), 128)))
                                                .subscribeOn(Schedulers.boundedElastic()), 1)
                                        .flatMapIterable(batch -> batch)
                                        .concatMap(event -> {
                                            cursor.set(event.seq());
                                            List<ThreadView.IncrementalEvent> mapped =
                                                    projection.apply(List.of(event));
                                            String eventId = ChatHistory.cursor(id, event.seq());
                                            return Flux.fromIterable(mapped)
                                                    .map(value -> ServerSentEvent.<String>builder(
                                                                    JsonUtils.getJsonCodec().toJson(value))
                                                            .event("view").id(eventId).build());
                                        });
                        Flux<ServerSentEvent<String>> heartbeat =
                                Flux.interval(Duration.ofSeconds(15))
                                        .map(tick -> ServerSentEvent.<String>builder()
                                                .comment("keepalive").build());
                        return Flux.merge(committed, heartbeat)
                                .startWith(ServerSentEvent.<String>builder().comment("connected").build());
                    });
        });
    }

    /**
     * 审批提交入口，所有阻塞日志操作切至弹性线程池。
     *
     * <p>202 表示新决定已持久接收，200 表示查回已有同一决定；二者都不保证工具成功。
     * 状态冲突返回 409 并附当前视图；视图暂不可读时明确需要核对，不编造接收失败。
     */
    @PostMapping("/threads/{id}/interactions/{requestId}/answer")
    public Mono<ResponseEntity<String>> answer(@PathVariable String id,
            @PathVariable String requestId, @RequestBody ApprovalAnswerRequest body) {
        return AuthRequests.call(() -> {
            var receipt = sessions.answer(id, requestId, body.decision(), body.reason());
            return ResponseEntity.status(receipt.created() ? 202 : 200)
                    .body(JsonUtils.getJsonCodec().toJson(receipt));
        }).onErrorResume(IllegalStateException.class, error -> currentConflict(id, error));
    }

    /** 取消逻辑任务而非关闭页面；只支持等待阶段，重复取消返回已记录的结果。 */
    @PostMapping("/threads/{id}/turns/{turnId}/cancel")
    public Mono<ResponseEntity<String>> cancel(@PathVariable String id, @PathVariable String turnId) {
        return AuthRequests.call(() -> ResponseEntity.ok(JsonUtils.getJsonCodec()
                        .toJson(sessions.cancelPending(id, turnId))))
                .onErrorResume(IllegalStateException.class, error -> currentConflict(id, error));
    }

    /** 冲突不是用户的拒绝决定；返回事实帮助旧设备恢复，而不是静默覆盖先前答复。 */
    private Mono<ResponseEntity<String>> currentConflict(String id, IllegalStateException error) {
        return AuthRequests.call(() -> {
            Object current;
            try {
                current = sessions.view(id);
            } catch (RuntimeException unavailable) {
                current = Map.of("status", "reconciling");
            }
            return ResponseEntity.status(409).body(JsonUtils.getJsonCodec().toJson(
                    Map.of("error", error.getMessage(), "currentView", current)));
        });
    }

    /**
     * 从双日志发现已持久化的交互变化，每个订阅首次都通知，断线后无需碰巧等到新事件。
     *
     * <p>该通道不承载卡片数据，不设置执行日志事件 ID；前端收到通知后读统一视图。
     * 普通消息仍走原 view/events 增量流。重启后从日志重新发现变化，不依赖易失内存通知。
     */
    @GetMapping(value = "/threads/{id}/interactions/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> interactionEvents(@PathVariable String id) {
        return CurrentUser.requireUserId().flatMapMany(userId -> {
            String uid = String.valueOf(userId);
            Flux<ServerSentEvent<String>> changes = Flux.interval(Duration.ZERO, Duration.ofMillis(500))
                    .onBackpressureDrop()
                    .concatMap(tick -> Mono.fromCallable(() ->
                                    UserContext.call(uid, () -> sessions.interactionRevision(id)))
                            .subscribeOn(Schedulers.boundedElastic()), 1)
                    .distinctUntilChanged()
                    .map(revision -> ServerSentEvent.<String>builder("{\"type\":\"hitl-view-changed\"}")
                            .event("hitl-view-changed").build());
            Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                    .map(tick -> ServerSentEvent.<String>builder().comment("keepalive").build());
            return Flux.merge(changes, heartbeat);
        });
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler(SessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(SessionNotFoundException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException error) {
        return Map.of("error", error.getMessage());
    }
}
