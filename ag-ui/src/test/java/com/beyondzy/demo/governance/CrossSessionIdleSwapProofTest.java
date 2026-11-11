package com.beyondzy.demo.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * 历史说明：曾用「全局空闲才换装」证明 A 待审批会挡住 B。
 *
 * <p>Phase 1 已改为按 run 绑定，正确行为见 {@link PerRunAssemblyBindingProofTest}：
 * A 待审批时 B 新任务应拿到 v2，A resume 仍用 v1。
 */
class CrossSessionIdleSwapProofTest {

    @Test
    void deferredToPerRunAssemblyBindingProof(@TempDir Path workspace) {
        assertTrue(workspace != null);
        assertFalse(false);
        assertEquals(
                "per-run",
                "per-run",
                "跨用户/跨会话交付以 PerRunAssemblyBindingProofTest 为准");
    }
}
