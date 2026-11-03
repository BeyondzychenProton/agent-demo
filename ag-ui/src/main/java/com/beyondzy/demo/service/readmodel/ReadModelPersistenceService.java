package com.beyondzy.demo.service.readmodel;

import com.beyondzy.demo.domain.readmodel.ReadModelChangeRowBean;
import com.beyondzy.demo.domain.readmodel.ReadModelContentBlockBean;
import com.beyondzy.demo.domain.readmodel.ReadModelHeadBean;
import java.util.List;

/** 读模型持久化应用服务。对应表族：rm_*；负责事务边界与 Mapper 委托，不含截断/指纹业务。 */
public interface ReadModelPersistenceService {

    /** 读取会话水位与检查点。 */
    ReadModelHeadBean state(String thread);

    /** 读取原始条目载荷。 */
    String raw(String thread, String id);

    /** 读取当前版本条目的排序键。 */
    Long order(String thread, String id);

    /** 读取会话内最大排序键。 */
    long maxOrder(String thread);

    /** 按游标分页读取 pending 条目 payload。 */
    List<String> pending(String thread, long after, int limit);

    /** 统计会话 pending 条数。 */
    int pendingCount(String thread);

    /** 同事务清空指定会话的全部读模型数据。 */
    void invalidate(String thread);

    /** 读取当前版本条目 payload；不存在时返回 null。 */
    String findCurrentPayload(String thread, String id);

    /**
     * 同事务执行一批写指令：raws → 各 item（关闭旧版/插入新版/变更/pending/分块）→ head。
     * 顺序与迁前 JDBC commit 一致，保证水位与条目一起提交。
     */
    void commit(CommitBatch batch);

    /**
     * 按 order_key 倒序分页读取 payload。
     * currentRevision 为 true 时只读当前打开版本；否则按历史水位可见性读取。
     */
    List<String> findPagePayloads(String thread, long revision, long before, int limit, boolean currentRevision);

    /** 读取变更表最小 revision；无记录时返回 0。 */
    long findMinChangeRevision(String thread);

    /** 读取指定 revision 之后的变更行。 */
    List<ReadModelChangeRowBean> findChangesAfter(String thread, long after, int limit);

    /** 读取内容分块。 */
    List<ReadModelContentBlockBean> findContentBlocks(
            String thread, String id, long revision, String field, int after, int limit);

    /**
     * 同事务清理过期变更、旧版本条目及孤儿内容块。
     * 先删变更与过期 item，再清无引用的 content/block。
     */
    void cleanup(long changeTtlCutoff, long snapshotTtlCutoff);
}
