package com.beyondzy.demo.readmodel;

import com.beyondzy.demo.domain.readmodel.ReadModelChangeRowBean;
import com.beyondzy.demo.domain.readmodel.ReadModelContentBlockBean;
import com.beyondzy.demo.domain.readmodel.ReadModelHeadBean;
import com.beyondzy.demo.readmodel.CommitBatch;
import com.beyondzy.demo.readmodel.ReadModelPersistenceService;
import com.beyondzy.demo.readmodel.ChatItems;
import com.beyondzy.demo.support.DbTimes;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** 展示读模型编排：截断、指纹、游标与 HITL 判定；SQL 与事务委托 PersistenceService。 */
public final class ReadModelStore implements AutoCloseable {
    public static final long SNAPSHOT_TTL = 30 * 60_000L;
    public static final long CHANGE_TTL = 24 * 60 * 60_000L;
    public record State(String generation, long revision, long execSeq, long inboxSeq,
                        long updatedAt, String leaseTag, String checkpoint, String control) {}
    public record Detail(int bytes, String encoding) {}
    public record ViewItem(String itemId, long orderKey, long contentVersion,
                           Map<String, Object> item, Map<String, Detail> details) {}
    public record Change(String cursor, long revision, ViewItem item) {}
    public record Page(List<ViewItem> items, String snapshotToken, String nextCursor,
                       boolean hasMore, String eventCursor, Map<String, Long> sourceWatermark,
                       long projectionUpdatedAt) {}
    public record Content(List<String> blocks, String nextCursor, String encoding) {}
    private record Cursor(String thread, String generation, long revision, long before, long expires) {}
    private record PreviewResult(ViewItem item, List<CommitBatch.BlockWrite> blocks) {}

    private final ReadModelPersistenceService persistence;

    public ReadModelStore(ReadModelPersistenceService persistence) {
        this.persistence = Objects.requireNonNull(persistence, "读模型持久化服务不能为空");
    }

    @Deprecated(forRemoval = true)
    public ReadModelStore(DataSource dataSource) {
        throw new UnsupportedOperationException("请注入 ReadModelPersistenceService，不再直接使用 DataSource");
    }

    @Deprecated(forRemoval = true)
    public ReadModelStore(Path workspace) {
        throw new UnsupportedOperationException("旧文件型读模型存储已移除，请注入 ReadModelPersistenceService");
    }

    public synchronized State state(String thread) {
        ReadModelHeadBean row = persistence.state(thread);
        return row == null ? null : new State(row.getGeneration(), row.getRevision(), row.getExecSeq(),
                row.getInboxSeq(), DbTimes.toEpochMilli(row.getGmtModified()), row.getLeaseTag(),
                row.getCheckpoint(), row.getControl());
    }

    /** 仅清理单个会话的过期派生格式，权威 SessionLog 与 Inbox 完全不受影响。 */
    public synchronized void invalidate(String thread) {
        persistence.invalidate(thread);
    }

    public synchronized ChatItems.Item raw(String thread, String id) {
        String payload = persistence.raw(thread, id);
        return payload == null ? null : decode(payload, ChatItems.Item.class);
    }

    public synchronized Long order(String thread, String id) {
        return persistence.order(thread, id);
    }

    public synchronized long maxOrder(String thread) {
        return persistence.maxOrder(thread);
    }

    public synchronized List<ViewItem> pending(String thread) { return pending(thread, 0, 20); }

    /** 未解决审批独立于历史窗口，较早的请求不会因为只加载尾页而消失。 */
    public synchronized List<ViewItem> pending(String thread, long after, int limit) {
        return persistence.pending(thread, after, limit).stream()
                .map(payload -> decode(payload, ViewItem.class)).toList();
    }

    public synchronized int pendingCount(String thread) {
        return persistence.pendingCount(thread);
    }

    /** 原始聚合项、页面版本、变更记录和双水位同时提交，不发布半批状态。 */
    public synchronized void commit(String thread, State previous, long execSeq, long inboxSeq,
                                    String leaseTag, String checkpoint, String control,
                                    List<ChatItems.Item> rawItems, Map<String, Object> views,
                                    Map<String, Long> orders) {
        long revision = previous == null ? 0 : previous.revision();
        String generation = previous == null ? UUID.randomUUID().toString() : previous.generation();
        long now = System.currentTimeMillis();
        try {
            List<CommitBatch.RawWrite> raws = new ArrayList<>();
            for (var item : rawItems) {
                raws.add(new CommitBatch.RawWrite(item.id(), json(item)));
            }
            List<CommitBatch.ItemWrite> items = new ArrayList<>();
            for (var entry : views.entrySet()) {
                String id = entry.getKey();
                long orderKey = orders.get(id);
                var dryRun = preview(id, orderKey, revision + 1, entry.getValue(), false);
                String fingerprint = json(new ViewItem(id, dryRun.item().orderKey(), 0, dryRun.item().item(), dryRun.item().details()));
                String old = currentFingerprint(thread, id);
                if (fingerprint.equals(old)) {
                    // 内容引用大小相同时仍可能改变；完整聚合项由调用方保证只把真正变动项送入。
                    // 对存在分块的项不能仅用预览判断，必须产生新版本。
                    if (dryRun.item().details().isEmpty()) continue;
                }
                revision++;
                var written = preview(id, orderKey, revision, entry.getValue(), true);
                Long pendingOrderKey = null;
                if (written.item().item().get("hitl") instanceof Map<?, ?> h
                        && Set.of("pending", "processing", "reconciling").contains(h.get("interactionStatus"))) {
                    pendingOrderKey = written.item().orderKey();
                }
                items.add(new CommitBatch.ItemWrite(
                        id, revision, written.item().orderKey(), json(written.item()),
                        pendingOrderKey, written.blocks()));
            }
            var head = new CommitBatch.HeadWrite(thread, generation, revision, execSeq, inboxSeq, now,
                    leaseTag, checkpoint, control);
            persistence.commit(new CommitBatch(List.copyOf(raws), List.copyOf(items), head));
        } catch (RuntimeException error) {
            throw new IllegalStateException("提交展示投影失败", error);
        }
    }

    private String currentFingerprint(String thread, String id) {
        String payload = persistence.findCurrentPayload(thread, id);
        if (payload == null) return null;
        var old = decode(payload, ViewItem.class);
        return json(new ViewItem(old.itemId(), old.orderKey(), 0, old.item(), old.details()));
    }

    /** 截断发生在写入指令组装时；读页不会先加载整条大消息再截取。 */
    @SuppressWarnings("unchecked")
    private PreviewResult preview(String id, long order, long version, Object value, boolean write) {
        Map<String, Object> fields = new LinkedHashMap<>(decode(json(value), Map.class));
        for (String label : List.of("displayTitle", "name")) {
            if (fields.get(label) instanceof String text && text.codePointCount(0, text.length()) > 256)
                fields.put(label, prefix(text, 256) + "…");
        }
        Map<String, Detail> details = new LinkedHashMap<>();
        List<CommitBatch.BlockWrite> blocks = new ArrayList<>();
        for (String field : List.of("text", "arguments", "result", "error", "progress", "hitl")) {
            Object content = fields.get(field);
            if (content == null) continue;
            String encoding = content instanceof String ? "text" : "json";
            String text = content instanceof String str ? str : json(content);
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes <= 4096) continue;
            details.put(field, new Detail(bytes, encoding));
            // 审批权限和请求编号保留，仅移走可能很大的参数字段。
            if (field.equals("hitl") && content instanceof Map<?, ?> map) {
                var small = new LinkedHashMap<String, Object>((Map<String, Object>) map);
                small.put("payload", "参数较长，请展开查看"); fields.put(field, small);
            } else fields.put(field, prefix(text, 1024) + "…");
            if (!write) continue;
            List<String> parts = blocks(text);
            for (int index = 0; index < parts.size(); index++) {
                String block = parts.get(index);
                blocks.add(new CommitBatch.BlockWrite(field, index, hash(block), block, encoding));
            }
        }
        return new PreviewResult(new ViewItem(id, order, version, fields, details), List.copyOf(blocks));
    }

    public synchronized Page page(String thread, int requested, String before) {
        State state = state(thread);
        if (state == null) throw new IllegalStateException("展示投影尚未建立");
        int limit = Math.max(1, Math.min(requested, 50));
        Cursor cursor = before == null || before.isBlank() ? new Cursor(thread, state.generation(), state.revision(),
                Long.MAX_VALUE, System.currentTimeMillis() + SNAPSHOT_TTL) : parse(before, thread, state);
        if (cursor.before() <= 0) throw new IllegalArgumentException("事件游标不能作为历史页游标");
        List<ViewItem> items = new ArrayList<>();
        int bytes = 2048;
        boolean more = false;
        boolean currentRevision = cursor.revision() == state.revision();
        List<String> payloads = persistence.findPagePayloads(
                thread, cursor.revision(), cursor.before(), limit + 1, currentRevision);
        for (String payload : payloads) {
            int nextSize = payload.getBytes(StandardCharsets.UTF_8).length;
            // 给状态回执及待答摘要预留预算，完整HTTP响应继续保持有界。
            if (items.size() == limit || bytes + nextSize > 192 * 1024) { more = true; break; }
            items.add(decode(payload, ViewItem.class)); bytes += nextSize;
        }
        String next = more ? token(new Cursor(thread, cursor.generation(), cursor.revision(),
                items.get(items.size() - 1).orderKey(), cursor.expires())) : null;
        Collections.reverse(items);
        return new Page(List.copyOf(items), token(cursor), next, more,
                eventCursor(thread, state.generation(), cursor.revision()),
                Map.of("execSeq", state.execSeq(), "inboxSeq", state.inboxSeq()), state.updatedAt());
    }

    public synchronized List<Change> changes(String thread, String after) {
        State state = state(thread);
        if (state == null) return List.of();
        Cursor cursor = parse(after, thread, state);
        if (cursor.before() != 0) throw new IllegalArgumentException("历史页游标不能作为事件游标");
        long minimum = persistence.findMinChangeRevision(thread);
        if (cursor.revision() < state.revision() && (minimum == 0 || cursor.revision() < minimum - 1))
            throw new Gone("补读记录已过期，请重新加载");
        List<Change> result = new ArrayList<>();
        for (ReadModelChangeRowBean row : persistence.findChangesAfter(thread, cursor.revision(), 50)) {
            long revision = row.getRevision();
            result.add(new Change(eventCursor(thread, state.generation(), revision), revision,
                    decode(row.getPayload(), ViewItem.class)));
        }
        return result;
    }

    public synchronized Content content(String thread, String id, long version, String field, int after) {
        if (version < 1 || after < 0) throw new IllegalArgumentException("非法内容位置");
        var blocks = new ArrayList<String>(); String encoding = "text"; boolean more = false; int bytes = 256;
        for (ReadModelContentBlockBean row : persistence.findContentBlocks(thread, id, version, field, after, 17)) {
            String body = row.getBody();
            int size = json(body).getBytes(StandardCharsets.UTF_8).length;
            if (blocks.size() == 16 || bytes + size > 120 * 1024) { more = true; break; }
            blocks.add(body); bytes += size; encoding = row.getEncoding();
        }
        if (blocks.isEmpty()) throw new Gone("内容版本不存在或已过期");
        return new Content(List.copyOf(blocks), more ? Integer.toString(after + blocks.size()) : null, encoding);
    }

    /** 清理仅影响过期派生版本；当前版本以及补读窗口内的版本一直保留。 */
    public synchronized void cleanup() {
        long now = System.currentTimeMillis();
        persistence.cleanup(now - CHANGE_TTL, now - SNAPSHOT_TTL);
    }

    public static String eventCursor(String thread, String generation, long revision) {
        return token(new Cursor(thread, generation, revision, 0, Long.MAX_VALUE));
    }

    private static Cursor parse(String token, String thread, State state) {
        try {
            Cursor cursor = decode(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8), Cursor.class);
            if (!thread.equals(cursor.thread()) || cursor.revision() < 0 || cursor.revision() > state.revision())
                throw new IllegalArgumentException("游标不属于当前会话或超出水位");
            if (!state.generation().equals(cursor.generation()) || cursor.expires() < System.currentTimeMillis())
                throw new Gone("历史版本已过期，请重新加载");
            return cursor;
        } catch (Gone error) { throw error; }
        catch (RuntimeException error) { throw new IllegalArgumentException("非法展示游标", error); }
    }

    public static class Gone extends RuntimeException { public Gone(String message) { super(message); } }
    private static String token(Cursor value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json(value).getBytes(StandardCharsets.UTF_8));
    }
    public static String json(Object value) { return JsonUtils.getJsonCodec().toJson(value); }
    public static <T> T decode(String text, Class<T> type) { return JsonUtils.getJsonCodec().fromJson(text, type); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    public static List<String> blocks(String text) {
        var result = new ArrayList<String>(); var current = new StringBuilder(); int bytes = 0;
        for (int index = 0; index < text.length();) {
            int cp = text.codePointAt(index), size = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes + size > 8192) { result.add(current.toString()); current.setLength(0); bytes = 0; }
            current.appendCodePoint(cp); bytes += size; index += Character.charCount(cp);
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }
    private static String prefix(String text, int codePoints) {
        return text.substring(0, text.offsetByCodePoints(0, Math.min(codePoints, text.codePointCount(0, text.length()))));
    }

    @Override
    public synchronized void close() {
        // 无本地 JDBC 连接可关闭；持久化由 Spring 管理的 PersistenceService 负责。
    }
}
