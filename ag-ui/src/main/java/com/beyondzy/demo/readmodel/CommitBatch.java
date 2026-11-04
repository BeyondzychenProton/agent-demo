package com.beyondzy.demo.readmodel;

import java.util.List;

/**
 * 读模型一次提交的写指令批次。
 * 由 Store 完成预览/指纹/HITL 判定后组装；PersistenceService 按 raws → items → head 顺序同事务执行。
 */
public record CommitBatch(List<RawWrite> raws, List<ItemWrite> items, HeadWrite head) {

    /** 原始条目幂等写入。 */
    public record RawWrite(String id, String payload) {}

    /**
     * 单条目版本写入指令。
     * pendingOrderKeyOrNull 非空时表示关闭旧 pending 后重新登记；blocks 仅在 write 预览产生分块时非空。
     */
    public record ItemWrite(
            String id,
            long revision,
            long orderKey,
            String payload,
            Long pendingOrderKeyOrNull,
            List<BlockWrite> blocks) {}

    /** 长字段分块：先幂等写 rm_block，再写 rm_content 映射。 */
    public record BlockWrite(String field, int blockNo, String hash, String body, String encoding) {}

    /** 会话水位 upsert。 */
    public record HeadWrite(
            String thread,
            String generation,
            long revision,
            long execSeq,
            long inboxSeq,
            long updatedAt,
            String leaseTag,
            String checkpoint,
            String control) {}
}
