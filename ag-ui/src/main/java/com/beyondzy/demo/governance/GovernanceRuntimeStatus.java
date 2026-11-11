package com.beyondzy.demo.governance;

import java.time.Instant;

/** 交付运行诊断：当前采用的 Snapshot、刷新时间与失败原因。 */
public record GovernanceRuntimeStatus(
        String adoptedSnapshotId,
        String adoptedEtag,
        Instant lastRefreshAt,
        String lastFailure,
        boolean autoRefreshEnabled) {}
