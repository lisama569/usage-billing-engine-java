package com.github.lisama569.usagebillingenginejava.api.dto;

/**
 * 事件查询视图。reversed=true 表示已冲正。
 */
public record UsageEventView(
        String eventId,
        String accountId,
        String metric,
        long quantity,
        long eventTimeEpochMs,
        long receivedAtEpochMs,
        String period,
        boolean reversed,
        String correctionId
) {
}
