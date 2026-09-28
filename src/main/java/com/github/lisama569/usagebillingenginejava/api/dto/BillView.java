package com.github.lisama569.usagebillingenginejava.api.dto;

import java.util.List;

/** 账单视图（某账期最新版本快照）。 */
public record BillView(
        String accountId,
        String period,
        int version,
        String status,
        long totalQuantity,
        long reversedQuantity,
        long totalAmount,
        List<BillLineView> lines,
        long createdAtEpochMs
) {
}
