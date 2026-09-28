package com.github.lisama569.usagebillingenginejava.domain;

import java.time.Instant;

/**
 * 人工纠错单。
 * USAGE_DELTA：带符号用量（负=冲正），对应一条 CORRECTION 事件；
 * AMOUNT_ADJUSTMENT：带符号金额调整行。
 */
public record Correction(
        String correctionId,
        String accountId,
        String period,
        Type type,
        String metric,
        long signedQuantity,
        long signedAmountMinor,
        String reason,
        String linkedEventId,
        Instant createdAt
) {
    public enum Type {
        USAGE_DELTA,
        AMOUNT_ADJUSTMENT
    }
}
