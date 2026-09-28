package com.github.lisama569.usagebillingenginejava.domain;

import java.time.Instant;

/** 一条用量事件。纠错事件 quantity 允许为负（冲正）。 */
public record UsageEvent(
        String eventId,
        String accountId,
        String metric,
        long quantity,
        Instant eventTime,
        Instant recordedAt,
        EventKind kind,
        String correctionId
) {
}
