package com.beyondzy.demo.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 数据库 DATETIME(3) 与毫秒时间戳互转，时区与 JDBC Asia/Shanghai 一致。 */
public final class DbTimes {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private DbTimes() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static LocalDateTime ofEpochMilli(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZONE);
    }

    public static long toEpochMilli(LocalDateTime time) {
        if (time == null) return 0L;
        return time.atZone(ZONE).toInstant().toEpochMilli();
    }
}
