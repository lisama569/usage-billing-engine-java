package com.github.lisama569.usagebillingenginejava.service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/** 账期工具：账期=eventTime 所在 UTC 自然月，键格式 yyyy-MM，与接收顺序无关。 */
public final class PeriodKeys {

    private PeriodKeys() {
    }

    public static String ofEpochMs(long epochMs) {
        return YearMonth.from(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)).toString();
    }

    public static String current() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }
}
