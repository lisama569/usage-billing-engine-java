package com.github.lisama569.usagebillingenginejava.domain;

import java.time.Instant;
import java.util.List;

/** 账期账单的一个不可变版本；重算只会产生新版本，旧版本永久保留可复现。 */
public record Bill(
        String accountId,
        String period,
        int version,
        boolean finalized,
        List<BillLine> lines,
        long totalQuantity,
        long totalAmountMinor,
        int eventCount,
        int correctionCount,
        Instant computedAt
) {
}
