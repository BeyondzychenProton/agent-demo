package com.beyondzy.demo.web.readmodel;

import com.beyondzy.demo.readmodel.ProjectionService;
import com.beyondzy.demo.readmodel.ReadModelStore;
import com.beyondzy.demo.session.ChatSessions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 有界历史和内容块接口；旧接口继续保留兼容，不把部分历史伪装成完整快照。 */
@RestController
@ConditionalOnProperty(name = "chat.read-model-v2-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(value = "/api/v2/threads", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ReadModelController {
    private final ProjectionService projection;
    private final ChatSessions sessions;
    public ReadModelController(ChatSessions sessions) { this.sessions = sessions; projection = sessions.projection(); }

    @GetMapping
    public Mono<String> threads(@RequestParam(defaultValue = "20") int limit,
                                @RequestParam(required = false) String cursor) {
        return Mono.fromCallable(() -> ReadModelStore.json(sessions.threads(limit, cursor)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/view")
    public Mono<String> view(@PathVariable String id, @RequestParam(defaultValue = "50") int limit,
                             @RequestParam(required = false) String before) {
        return Mono.fromCallable(() -> {
            projection.watch(id);
            var page = projection.store().page(id, limit, before);
            var result = new LinkedHashMap<String, Object>();
            result.put("page", page); result.put("status", projection.status(id));
            result.put("pending", projection.store().pending(id).stream().map(item -> {
                var hitl = (Map<?, ?>) item.item().get("hitl");
                return Map.of("itemId", item.itemId(), "requestId", hitl.get("requestId"),
                        "status", hitl.get("interactionStatus"));
            }).toList());
            return ReadModelStore.json(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/interactions")
    public Mono<String> interactions(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
                                     @RequestParam(defaultValue = "20") int limit) {
        return Mono.fromCallable(() -> {
            projection.watch(id);
            var items = projection.store().pending(id, after, limit);
            return ReadModelStore.json(Map.of("items", items, "total", projection.store().pendingCount(id),
                    "nextCursor", items.isEmpty() ? "" : Long.toString(items.get(items.size() - 1).orderKey())));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/items/{itemId}/content")
    public Mono<String> content(@PathVariable String id, @PathVariable String itemId,
            @RequestParam long version, @RequestParam String field, @RequestParam(defaultValue = "0") int cursor) {
        return Mono.fromCallable(() -> {
            projection.watch(id);
            return ReadModelStore.json(projection.store().content(id, itemId, version, field, cursor));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(@PathVariable String id, @RequestParam String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return Flux.defer(() -> {
            var cursor = new AtomicReference<>(lastEventId == null || lastEventId.isBlank() ? after : lastEventId);
            var status = new AtomicReference<>("");
            Flux<ServerSentEvent<String>> changes = Flux.interval(Duration.ZERO, Duration.ofMillis(100))
                    .onBackpressureDrop().concatMap(tick -> Mono.fromCallable(() -> {
                        projection.watch(id);
                        var frames = new ArrayList<ServerSentEvent<String>>();
                        for (var change : projection.store().changes(id, cursor.get())) {
                            frames.add(ServerSentEvent.<String>builder(ReadModelStore.json(change))
                                    .event("item").id(change.cursor()).build());
                            cursor.set(change.cursor());
                        }
                        String current = ReadModelStore.json(projection.status(id));
                        if (!current.equals(status.getAndSet(current)))
                            frames.add(ServerSentEvent.<String>builder(current).event("status").build());
                        return frames;
                    }).subscribeOn(Schedulers.boundedElastic()), 1).flatMapIterable(frames -> frames)
                    .onErrorResume(ReadModelStore.Gone.class, error -> Flux.just(ServerSentEvent.<String>builder(
                            "{\"reason\":\"历史水位已过期\"}").event("reset-required").build()));
            var heartbeat = Flux.interval(Duration.ofSeconds(15)).map(tick ->
                    ServerSentEvent.<String>builder().comment("keepalive").build());
            return Flux.merge(changes, heartbeat).takeUntil(frame -> "reset-required".equals(frame.event()))
                    .onBackpressureBuffer(32, ignored -> {}, reactor.core.publisher.BufferOverflowStrategy.ERROR);
        });
    }

    @ExceptionHandler(ReadModelStore.Gone.class)
    public ResponseEntity<Map<String, String>> gone(ReadModelStore.Gone error) {
        return ResponseEntity.status(410).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
}
