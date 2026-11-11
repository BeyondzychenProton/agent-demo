package com.beyondzy.demo.governance.adapter;

import com.beyondzy.demo.governance.GovernanceAssemblySupport;
import com.beyondzy.demo.governance.GovernanceClient;
import com.beyondzy.demo.governance.GovernanceGateway;
import com.beyondzy.demo.governance.GovernanceSnapshot;
import com.beyondzy.demo.governance.GovernanceSnapshotFetch;
import com.beyondzy.demo.governance.PreparedGovernanceAssembly;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * AgentScope 治理适配器：一次运行时接入点。
 *
 * <p>第二宿主只需配置治理地址与 Agent 令牌，调用 {@link #attach(Path, String, String)}
 * 即可得到可注册到模型的 Toolkit、Skill 提示词片段与 {@code snapshotId}。
 * 不依赖 ChatSessions / AG-UI / SessionLog。
 */
public final class AgentScopeGovernanceAdapter {

    private AgentScopeGovernanceAdapter() {}

    /**
     * 配置并拉取当前 Snapshot，准备完整装配。
     *
     * @param workspace 本地工具工作区（可为临时目录）
     * @param governanceBaseUrl 治理 HTTP 基址
     * @param agentToken Agent 应用令牌
     */
    public static AttachedAssembly attach(Path workspace, String governanceBaseUrl, String agentToken) {
        Objects.requireNonNull(workspace, "workspace");
        return attach(governanceBaseUrl, agentToken);
    }

    /** 使用应用身份接入。治理端按工具策略决定是否还需要可信用户身份。 */
    public static AttachedAssembly attach(String governanceBaseUrl, String agentToken) {
        return attach(GovernanceClient.forApplication(governanceBaseUrl, agentToken));
    }

    /** 使用已有网关（便于单测注入）准备当前装配。 */
    public static AttachedAssembly attach(Path workspace, GovernanceGateway gateway) {
        Objects.requireNonNull(workspace, "workspace");
        return attach(gateway);
    }

    private static AttachedAssembly attach(GovernanceGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        GovernanceSnapshotFetch fetch = gateway.fetchCurrent(null);
        if (fetch.notModified() || fetch.snapshot() == null) {
            throw new IllegalStateException("无条件拉取不应返回空或 304");
        }
        PreparedGovernanceAssembly prepared = GovernanceAssemblySupport.prepare(
                new Toolkit(), gateway, fetch.snapshot(), fetch.etag(), false);
        return AttachedAssembly.from(prepared);
    }

    /** 按历史 snapshotId 重建装配（进程恢复 / 按 run 绑定）。 */
    public static AttachedAssembly attachSnapshot(
            Path workspace, GovernanceGateway gateway, String snapshotId) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(gateway, "gateway");
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId 不能为空");
        }
        GovernanceSnapshot snapshot = gateway.fetchSnapshot(snapshotId);
        PreparedGovernanceAssembly prepared = GovernanceAssemblySupport.prepare(
                new Toolkit(), gateway, snapshot, snapshotId, false);
        return AttachedAssembly.from(prepared);
    }

    /** 第二宿主可见的装配结果：工具表、Skill 段、版本。 */
    public record AttachedAssembly(
            String snapshotId,
            String etag,
            Toolkit toolkit,
            String skillsSection,
            List<String> toolNames,
            PreparedGovernanceAssembly prepared) {

        static AttachedAssembly from(PreparedGovernanceAssembly prepared) {
            List<String> names = prepared.mount() == null
                    ? List.of()
                    : List.copyOf(prepared.mount().registeredNames());
            return new AttachedAssembly(
                    prepared.snapshotId(),
                    prepared.etag(),
                    prepared.toolkit(),
                    prepared.skillsSection() == null ? "" : prepared.skillsSection(),
                    names,
                    prepared);
        }
    }
}
