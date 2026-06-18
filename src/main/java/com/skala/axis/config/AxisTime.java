package com.skala.axis.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class AxisTime {
    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private AxisTime() {
    }

    public static LocalDate today() {
        return LocalDate.now(SERVICE_ZONE);
    }

    public static LocalDateTime localDateTimeNow() {
        return LocalDateTime.now(SERVICE_ZONE);
    }
}
