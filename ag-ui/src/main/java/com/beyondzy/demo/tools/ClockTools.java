package com.beyondzy.demo.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import reactor.core.publisher.Mono;

/**
 * 读取当前时间。空时区按 Asia/Shanghai。非法时区返回错误文本，不把异常抛出聊天。
 *
 * <p>只读且可并发。脚本和技能文件都不由本工具执行。
 */
public final class ClockTools {
    private static final String DEFAULT_ZONE = "Asia/Shanghai";

    @Tool(
            name = "get_current_time",
            description = "读取当前时间。timezone 留空时使用 Asia/Shanghai，返回带偏移的 ISO-8601 时间。",
            readOnly = true,
            concurrencySafe = true)
    public Mono<ToolResultBlock> getCurrentTime(
            @ToolParam(name = "timezone", description = "IANA 时区，例如 Asia/Shanghai 或 UTC。留空则使用 Asia/Shanghai。")
            String timezone) {
        return Mono.fromCallable(() -> ToolResultBlock.text(format(timezone)));
    }

    static String format(String timezone) {
        String requested = timezone == null ? "" : timezone.trim();
        String zoneId = requested.isEmpty() ? DEFAULT_ZONE : requested;
        try {
            return OffsetDateTime.now(ZoneId.of(zoneId)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeException error) {
            return "无效时区: " + zoneId;
        }
    }
}
