package com.beyondzy.demo.service.skill;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** 新技能业务表按 UTC 读写，接口输出带 Z 的 ISO 8601。不沿用旧表的上海时区。 */
public final class SkillTimes {
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private SkillTimes() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    public static String format(LocalDateTime utc) {
        if (utc == null) return null;
        return utc.truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC).format(ISO);
    }
}
