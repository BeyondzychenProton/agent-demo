package com.beyondzy.demo.governance;

import java.util.Map;

/**
 * 治理底座的最小出口：只拉当前 Snapshot，并按已绑定的 uri / snapshotId 提交 invoke。
 */
public interface GovernanceGateway {

    GovernanceSnapshot currentSnapshot();

    GovernanceInvokeResult invoke(String uri, String snapshotId, Map<String, Object> arguments);
}
