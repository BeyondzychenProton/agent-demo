package com.beyondzy.demo.governance;

import java.util.Map;

/**
 * 治理底座的最小出口：拉当前 Snapshot、按绑定提交 invoke，以及按需拉取 Skill 正文。
 */
public interface GovernanceGateway {

    GovernanceSnapshot currentSnapshot();

    /**
     * 条件读取当前 Snapshot。
     *
     * @param ifNoneMatch 上次成功采用的不透明 ETag；null 表示无条件拉取
     */
    default GovernanceSnapshotFetch fetchCurrent(String ifNoneMatch) {
        GovernanceSnapshot snapshot = currentSnapshot();
        return GovernanceSnapshotFetch.of(snapshot, null);
    }

    /** 按 id 读取历史 Snapshot（进程恢复原绑定时使用）。 */
    default GovernanceSnapshot fetchSnapshot(String snapshotId) {
        throw new UnsupportedOperationException("未实现历史 Snapshot 读取");
    }

    GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments);

    /** 读取快照钉住的 Skill 正文（UTF-8 明文）。 */
    String fetchInstructions(String snapshotId, String skillId);

    default String fetchResource(String snapshotId, String skillId, String relativePath) {
        throw new UnsupportedOperationException("Skill resource reading is unavailable");
    }
}
