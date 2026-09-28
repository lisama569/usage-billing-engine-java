package com.github.lisama569.usagebillingenginejava.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 账期工具：自然月（UTC），键格式 yyyy-MM。 */
public final class Periods {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private Periods() {
    }

    public static String of(Instant eventTime) {
        return MONTH.format(eventTime);
    }
}
