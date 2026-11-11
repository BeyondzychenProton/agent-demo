package com.beyondzy.demo.governance;

/**
 * 条件读取当前 Snapshot 的结果。
 *
 * <p>{@code notModified=true} 时 {@code snapshot} 为 null，客户端应继续使用已成功装配版本。
 * {@code etag} 为不透明 HTTP 校验值（通常等于响应体内容指纹）。
 */
public record GovernanceSnapshotFetch(GovernanceSnapshot snapshot, String etag, boolean notModified) {

    public static GovernanceSnapshotFetch of(GovernanceSnapshot snapshot, String etag) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot 不能为空");
        return new GovernanceSnapshotFetch(snapshot, etag, false);
    }

    public static GovernanceSnapshotFetch notModified(String etag) {
        return new GovernanceSnapshotFetch(null, etag, true);
    }
}
