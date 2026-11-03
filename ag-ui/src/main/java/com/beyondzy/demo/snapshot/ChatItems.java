package com.beyondzy.demo.snapshot;

import com.beyondzy.demo.support.Payloads;
import io.agentscope.core.agent.accumulator.TextAccumulator;
import io.agentscope.core.agent.accumulator.ThinkingAccumulator;
import io.agentscope.core.agent.accumulator.ToolCallsAccumulator;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 从已提交事件前缀重建页面项：既重建已完成项，也重建未落定的进行中项。
 *
 * <p>对应大纲 6.1.6 / 5.4.6 的 M4 服务端重建：合并分片的工具参数、重建已写出的半段文本、
 * 按 toolCallId 聚合成卡片、run/end 时收口中间态。支持全量重放和持久化增量投影。
 */
public final class ChatItems {

    /**
     * 一条页面项：消息或工具卡片。
     */
    public record Item(
            String id,
            String kind,
            String role,
            String turnId,
            String runId,
            long seq,
            long startedAt,
            long endedAt,
            String status,
            List<ContentBlock> content,
            List<ContentBlock> progress,
            ToolUseBlock call,
            ToolResultBlock result,
            String arguments) {
    }

    /**
     * 聚合中的一个条目（可变），freeze() 后产出不可变 Item。
     */
    private static class Entry {
        final String id;
        final String kind;
        final String role;
        final String turn;
        final String run;
        final long seq;
        final long startedAt;
        long endedAt;
        String status;
        List<ContentBlock> content = List.of();
        final List<ContentBlock> progress = new ArrayList<>();
        final StringBuilder arguments = new StringBuilder();
        ToolUseBlock call;
        ToolResultBlock result;

        Entry(String id, String kind, String role, SessionEvent event, String status) {
            this.id = id;
            this.kind = kind;
            this.role = role;
            turn = event.turnId();
            run = event.executionRunId();
            seq = event.seq();
            startedAt = event.occurredAt();
            this.status = status;
        }

        Entry(Item saved) {
            id = saved.id(); kind = saved.kind(); role = saved.role(); turn = saved.turnId();
            run = saved.runId(); seq = saved.seq(); startedAt = saved.startedAt();
            endedAt = saved.endedAt(); status = saved.status(); call = saved.call(); result = saved.result();
            content = saved.content().stream().filter(block -> !(block instanceof ToolUseBlock)
                    && !(block instanceof ToolResultBlock)).toList();
            progress.addAll(saved.progress()); arguments.append(saved.arguments());
        }

        Item freeze() {
            var blocks = new ArrayList<>(content);
            if (call != null) blocks.add(call);
            if (result != null) blocks.add(result);
            return new Item(
                    id, kind, role, turn, run, seq, startedAt, endedAt, status,
                    List.copyOf(blocks), List.copyOf(progress), call, result,
                    arguments.toString());
        }
    }

    /**
     * 一次模型输出的草稿：文本、思考、工具调用分别用与运行时相同的累加器合并分片。
     */
    private static final class ModelDraft {
        final Entry item;
        final TextAccumulator text = new TextAccumulator();
        final ThinkingAccumulator thinking = new ThinkingAccumulator();
        final ToolCallsAccumulator tools = new ToolCallsAccumulator();
        boolean committed;

        ModelDraft(Entry item) {
            this.item = item;
        }

        void refresh() {
            var content = new ArrayList<ContentBlock>();
            if (thinking.hasContent()) content.add(thinking.buildAggregated());
            if (text.hasContent()) content.add(text.buildAggregated());
            item.content = content;
        }
    }

    private final Map<String, Entry> items = new LinkedHashMap<>();
    private final Map<String, ModelDraft> models = new LinkedHashMap<>();
    private final Map<String, Entry> messages = new LinkedHashMap<>();
    private final Set<String> auxiliaryCalls = new HashSet<>();
    private final Set<String> dirty = new HashSet<>();
    private final java.util.function.Function<String, Item> loader;

    public ChatItems() { this(id -> null); }

    public ChatItems(java.util.function.Function<String, Item> loader) { this.loader = loader; }

    /** 只保存尚未收口的累加器；已完成项独立存储，避免每批重写整段历史。 */
    public record DraftState(String modelId, String itemId, List<ContentBlock> content,
                             List<ToolUseBlock> tools, String currentToolId, boolean committed) {}
    public record Checkpoint(int format, List<Item> openItems, Map<String, String> aliases,
                             List<DraftState> drafts, Set<String> auxiliaryCalls) {}

    public Checkpoint checkpoint() {
        var open = items.values().stream().map(Entry::freeze).toList();
        var aliases = new LinkedHashMap<String, String>();
        messages.forEach((key, entry) -> aliases.put(key, entry.id));
        var drafts = models.entrySet().stream().map(entry -> {
            var draft = entry.getValue();
            var tools = draft.tools.buildAllToolCalls().stream().map(call -> {
                var tool = items.get("tool:" + draft.item.turn + ":" + call.getId());
                // 框架 buildAllToolCalls 会把未闭合 JSON 内容替换为 {}，检查点必须保存原始参数前缀。
                return new ToolUseBlock(call.getId(), call.getName(), call.getInput(),
                        tool == null ? call.getContent() : tool.arguments.toString(), call.getMetadata(), call.getState());
            }).toList();
            return new DraftState(entry.getKey(), draft.item.id, draft.item.content, tools,
                    draft.tools.getCurrentToolCallId(), draft.committed);
        }).toList();
        return new Checkpoint(1, open, aliases, drafts, Set.copyOf(auxiliaryCalls));
    }

    public void restore(Checkpoint saved) {
        if (saved == null) return;
        if (saved.format() != 1) throw new IllegalArgumentException("展示累加器检查点格式已变更");
        saved.openItems().forEach(item -> items.put(item.id(), new Entry(item)));
        saved.aliases().forEach((key, id) -> messages.put(key, items.get(id)));
        for (var savedDraft : saved.drafts()) {
            var draft = new ModelDraft(items.get(savedDraft.itemId()));
            for (var block : savedDraft.content()) {
                if (block instanceof TextBlock text) draft.text.add(text);
                if (block instanceof ThinkingBlock thinking) draft.thinking.add(thinking);
            }
            savedDraft.tools().forEach(draft.tools::add);
            savedDraft.tools().stream().filter(call -> Objects.equals(call.getId(), savedDraft.currentToolId()))
                    .findFirst().ifPresent(call -> draft.tools.add(new ToolUseBlock(call.getId(), call.getName(),
                            Map.of(), null, call.getMetadata(), call.getState())));
            draft.committed = savedDraft.committed();
            models.put(savedDraft.modelId(), draft);
        }
        auxiliaryCalls.addAll(saved.auxiliaryCalls());
    }

    /** 事务提交后释放已落定条目；后续跨 run 工具结果可按稳定键从派生存储取回。 */
    public void compact() {
        models.entrySet().removeIf(entry -> entry.getValue().committed);
        var modelItems = models.values().stream().map(draft -> draft.item.id).collect(java.util.stream.Collectors.toSet());
        items.entrySet().removeIf(entry -> !modelItems.contains(entry.getKey())
                && Set.of("completed", "failed", "interrupted", "success", "error").contains(entry.getValue().status));
        messages.entrySet().removeIf(entry -> !items.containsKey(entry.getValue().id));
    }

    public List<Item> drainChanges() {
        var changed = items.values().stream().filter(item -> dirty.contains(item.id))
                .map(Entry::freeze).toList();
        dirty.clear();
        return changed;
    }

    private Entry existing(String id) {
        return items.computeIfAbsent(id, key -> {
            var saved = loader.apply(key);
            return saved == null ? null : new Entry(saved);
        });
    }

    static List<Item> read(List<SessionEvent> facts) {
        var view = new ChatItems();
        facts.forEach(view::apply);
        return view.items.values().stream()
                .filter(item -> !item.content.isEmpty() || item.call != null || item.result != null)
                .map(Entry::freeze)
                .toList();
    }

    /**
     * 逐条消费已提交事件，驱动状态机与累加器。
     */
    public void apply(SessionEvent event) {
        var data = event.data();
        String modelId = (String) data.get("modelCallId");
        switch (event.type()) {
            case "request/prepared" -> {
                // 辅助模型调用（非 REASONING）的 chunk 不进主视图
                if (!"REASONING".equals(data.get("purpose"))) auxiliaryCalls.add(modelId);
            }
            case "model/chunk" -> {
                if (auxiliaryCalls.contains(modelId)) return;
                var chunk = (Map<?, ?>) data.get("chunk");
                String chunkId = chunk.get("id") instanceof String id ? id : null;
                var draft =
                        models.computeIfAbsent(
                                modelId,
                                id -> {
                                    var entry =
                                            new Entry(
                                                    "message:"
                                                            + (chunkId == null
                                                                    ? "model-" + id
                                                                    : chunkId),
                                                    "message",
                                                    "assistant",
                                                    event,
                                                    "generating");
                                    items.put(entry.id, entry);
                                    return new ModelDraft(entry);
                                });
                if (draft.committed) return;
                dirty.add(draft.item.id);
                if (chunkId != null) messages.put(chunkId, draft.item);
                for (var value : (List<?>) chunk.get("content")) {
                    var block = decode(value, ContentBlock.class);
                    if (block instanceof TextBlock text) draft.text.add(text);
                    else if (block instanceof ThinkingBlock thinking) draft.thinking.add(thinking);
                    else if (block instanceof ToolUseBlock use) {
                        // 工具参数分片合并，与运行时使用同一套累加器处理交错参数
                        draft.tools.add(use);
                        String fragmentId =
                                use.getId() == null || use.getId().isBlank()
                                        ? draft.tools.getCurrentToolCallId()
                                        : use.getId();
                        for (var call : draft.tools.buildAllToolCalls()) {
                            var entry = tool(event, call.getId());
                            if (Objects.equals(call.getId(), fragmentId)
                                    && use.getContent() != null)
                                entry.arguments.append(use.getContent());
                            entry.call =
                                    new ToolUseBlock(
                                            call.getId(),
                                            call.getName(),
                                            call.getInput(),
                                            entry.arguments.toString(),
                                            call.getMetadata(),
                                            call.getState());
                            entry.status = "generating_arguments";
                        }
                    }
                }
                draft.refresh();
            }
            case "model/end" -> {
                var draft = models.get(modelId);
                if (draft != null && !draft.committed)
                    draft.item.status =
                            "completed".equals(data.get("status"))
                                    ? "finishing"
                                    : String.valueOf(data.get("status"));
                if (draft != null) dirty.add(draft.item.id);
            }
            case "migration/baseline" -> {
                // 压缩/基线迁移：把历史 context 消息灌回视图
                if (data.get("stateJson") instanceof String json)
                    AgentState.fromJsonString(json)
                            .getContext()
                            .forEach(message -> message(event, message));
            }
            case "message/user", "message/assistant", "tool/result", "turn/output" -> {
                if (data.get("message") != null)
                    message(event, Payloads.message(data.get("message")));
            }
            case "tool/requested" -> {
                var call = Payloads.toolUse(data.get("call"));
                var entry = tool(event, call.getId());
                entry.call = call;
                if (entry.arguments.isEmpty() && call.getContent() != null) {
                    entry.arguments.append(call.getContent());
                }
                if (entry.status.equals("generating_arguments")) entry.status = "requested";
            }
            case "tool/dispatch" -> {
                var entry = tool(event, (String) data.get("toolCallId"));
                entry.status = "running";
                if (entry.call == null)
                    entry.call =
                            ToolUseBlock.builder()
                                    .id((String) data.get("toolCallId"))
                                    .name((String) data.get("name"))
                                    .input(decode(data.get("arguments"), Map.class))
                                    .build();
                if (entry.arguments.isEmpty() && data.get("arguments") != null) {
                    entry.arguments.append(
                            JsonUtils.getJsonCodec().toJson(data.get("arguments")));
                }
            }
            case "tool/chunk" -> {
                // 执行进度进 progress 区
                var entry = tool(event, (String) data.get("toolCallId"));
                entry.status = "running";
                entry.progress.addAll(Payloads.toolResult(data.get("chunk")).getOutput());
            }
            case "action/end" -> {
                var entry = tool(event, (String) data.get("toolCallId"));
                if (data.get("result") != null)
                    result(event, entry, Payloads.toolResult(data.get("result")));
                else if (data.get("observation") instanceof Map<?, ?> observation)
                    entry.status = String.valueOf(observation.get("status")).toLowerCase();
                entry.endedAt = event.occurredAt();
            }
            case "interaction/requested" -> {
                // HITL 挂起分支：本 demo 不触发，保留状态机收口能力
                if (data.get("call") != null) {
                    var call = Payloads.toolUse(data.get("call"));
                    var entry = tool(event, call.getId());
                    entry.call = call;
                    entry.status = "suspended";
                }
            }
            case "run/end" -> {
                // run 结束时把仍停在中间态的本 run 卡片统一收口，避免刷新后永远转圈
                for (var entry : items.values())
                    if (Objects.equals(event.executionRunId(), entry.run)
                            && Set.of(
                                    "generating",
                                    "finishing",
                                    "running",
                                    "requested",
                                    "generating_arguments")
                            .contains(entry.status)) {
                        entry.status =
                                "completed".equals(data.get("status"))
                                        ? "completed"
                                        : "interrupted";
                        entry.endedAt = event.occurredAt();
                        dirty.add(entry.id);
                    }
                models.values().stream().filter(draft -> Objects.equals(event.executionRunId(), draft.item.run))
                        .forEach(draft -> draft.committed = true);
                auxiliaryCalls.clear();
            }
            default -> {
            }
        }
    }

    /**
     * 落定一条消息：拆出工具块归到卡片，其余文本进消息项。
     */
    private void message(SessionEvent event, Msg message) {
        if (message.getRole().name().equals("SYSTEM")) return;
        var content = new ArrayList<ContentBlock>();
        for (var block : message.getContent()) {
            if (block instanceof ToolUseBlock call) tool(event, call.getId()).call = call;
            else if (block instanceof ToolResultBlock result)
                result(event, tool(event, result.getId()), result);
            else content.add(block);
        }
        var entry = messages.get(message.getId());
        if (entry == null) entry = existing("message:" + message.getId());
        if (entry == null && !content.isEmpty()) {
            entry =
                    new Entry(
                            "message:" + message.getId(),
                            "message",
                            message.getRole().name().toLowerCase(),
                            event,
                            "completed");
            items.put(entry.id, entry);
            messages.put(message.getId(), entry);
        }
        if (entry != null) {
            dirty.add(entry.id);
            entry.content = content;
            entry.status = "completed";
            entry.endedAt = event.occurredAt();
            for (var draft : models.values()) if (draft.item == entry) draft.committed = true;
        }
    }

    /**
     * 按 turnId+toolCallId 取/建工具卡片条目。
     */
    private Entry tool(SessionEvent event, String callId) {
        String key = "tool:" + event.turnId() + ":" + callId;
        existing(key);
        dirty.add(key);
        return items.computeIfAbsent(key, id -> new Entry(id, "tool", "tool", event, "requested"));
    }

    private void result(SessionEvent event, Entry entry, ToolResultBlock result) {
        entry.result = result;
        entry.status = result.isSuspended() ? "suspended" : result.getState().name().toLowerCase();
        entry.endedAt = event.occurredAt();
    }

    private static <T> T decode(Object value, Class<T> type) {
        return JsonUtils.getJsonCodec().convertValue(value, type);
    }
}
