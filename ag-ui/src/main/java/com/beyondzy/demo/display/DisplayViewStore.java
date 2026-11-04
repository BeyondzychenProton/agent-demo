package com.beyondzy.demo.display;

import com.beyondzy.demo.domain.display.DisplayViewBean;
import com.beyondzy.demo.readmodel.ThreadView;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 完整基础视图的派生读索引；只保存展示数据，不保存审批授权与执行状态。 */
public final class DisplayViewStore {
    private static final int FORMAT = 1;
    public record Saved(long seq, boolean hasInteractions, ThreadView.SnapshotResponse view) {}
    private final DisplayIndexService displayIndex;

    public DisplayViewStore(DisplayIndexService displayIndex) {
        this.displayIndex = displayIndex;
    }

    @Deprecated(forRemoval = true)
    public DisplayViewStore(Path workspace) {
        throw new UnsupportedOperationException("旧文件型展示视图存储已移除，请注入 MySQL 数据源");
    }

    /** 水位和格式均匹配才返回；旧水位不能冒充当前页面。 */
    public synchronized Saved read(String id, long seq) {
        DisplayViewBean row = displayIndex.findView(id, seq);
        if (row == null || row.getFormat() != FORMAT) return null;
        try {
            var view = decode(row.getPayload());
            if (!id.equals(view.threadId()) || view.asOfSeq() != seq
                    || !((id + ":" + seq).equals(view.cursor())) || view.activeRun() != null) return null;
            return new Saved(seq, Boolean.TRUE.equals(row.getHasInteractions()), view);
        } catch (RuntimeException invalid) {
            // 派生数据解码失败视为未命中，由现有读取链重新生成，不影响权威日志。
            return null;
        }
    }

    /** 仅接收基础视图，禁止持久化带实时审批操作权限的装饰结果。 */
    public synchronized void save(String id, long seq, boolean hasInteractions, ThreadView.SnapshotResponse view) {
        if (!id.equals(view.threadId()) || view.asOfSeq() != seq || view.activeRun() != null)
            throw new IllegalArgumentException("展示视图水位不匹配或仍在执行");
        for (var item : view.view()) {
            if (item instanceof ThreadView.ToolCardViewModel card
                    && card.hitl() != null && card.hitl().canRespond())
                throw new IllegalArgumentException("不能持久化审批操作权限");
        }
        displayIndex.saveView(id, seq, FORMAT, hasInteractions, JsonUtils.getJsonCodec().toJson(view));
    }

    /** 按公开 kind 白名单恢复具体类型，避免给序列化器开放任意 Java 类型。 */
    @SuppressWarnings("unchecked")
    private static ThreadView.SnapshotResponse decode(String json) {
        Map<String, Object> root = JsonUtils.getJsonCodec().fromJson(json, Map.class);
        List<ThreadView.ChatItem> items = new ArrayList<>();
        for (var value : (List<Map<String, Object>>) root.get("view")) {
            items.add(switch (String.valueOf(value.get("kind"))) {
                case "message" -> convert(value, ThreadView.MessageItem.class);
                case "tool-card" -> convert(value, ThreadView.ToolCardViewModel.class);
                default -> throw new IllegalArgumentException("未知展示项类型");
            });
        }
        List<ThreadView.PendingInteraction> pending = new ArrayList<>();
        for (var value : (List<?>) root.get("pendingInteractions"))
            pending.add(convert(value, ThreadView.PendingInteraction.class));
        return new ThreadView.SnapshotResponse((String) root.get("threadId"), List.copyOf(items),
                List.copyOf(pending), ((Number) root.get("asOfSeq")).longValue(),
                (String) root.get("cursor"), root.get("activeRun") == null ? null
                        : convert(root.get("activeRun"), ThreadView.ActiveRun.class));
    }

    private static <T> T convert(Object value, Class<T> type) {
        return JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(value), type);
    }
}
