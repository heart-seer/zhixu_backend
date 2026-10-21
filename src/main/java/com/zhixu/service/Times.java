package com.zhixu.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public final class Times {
    private Times() {
    }

    /**
     * 接受 ISO-8601 带时区、带偏移或本地日期时间（按 UTC 解释）三种写法。
     */
    public static Instant parse(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException failed) {
                    throw ApiException.bad(field + " 不是合法的时间");
                }
            }
        }
    }

    public static Timestamp time(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }

    public static String iso(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.truncatedTo(ChronoUnit.MICROS).toString();
    }

    public static String zone(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw ApiException.bad("时区不能为空");
        }
        String name = value.trim();
        if (!ZoneId.getAvailableZoneIds().contains(name)) {
            throw ApiException.bad("时区不合法：" + name);
        }
        return name;
    }

    public static String timeOfDay(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw ApiException.bad(field + " 不能为空");
        }
        try {
            LocalTime time = LocalTime.parse(value.trim());
            return String.format("%02d:%02d:%02d", time.getHour(), time.getMinute(), time.getSecond());
        } catch (DateTimeParseException e) {
            throw ApiException.bad(field + " 不是合法的时间");
        }
    }
}
