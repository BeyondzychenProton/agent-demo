package com.beyondzy.demo.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

/** 当前时间工具：空时区用上海偏移，非法时区返回错误文本。 */
class ClockToolsTest {

    @Test
    void blankZoneUsesShanghaiOffset() {
        assertTrue(text(null).endsWith("+08:00"));
        assertTrue(text("").endsWith("+08:00"));
        assertTrue(text("   ").endsWith("+08:00"));
    }

    @Test
    void utcUsesZeroOffset() {
        String text = text("UTC");
        assertTrue(text.endsWith("Z") || text.endsWith("+00:00"), text);
    }

    @Test
    void invalidZoneReturnsErrorText() {
        String text = text("Not/AZone");
        assertTrue(text.contains("Not/AZone"), text);
        assertFalse(text.contains("+08:00"), text);
    }

    private static String text(String zone) {
        ToolResultBlock result = new ClockTools().getCurrentTime(zone).block();
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
