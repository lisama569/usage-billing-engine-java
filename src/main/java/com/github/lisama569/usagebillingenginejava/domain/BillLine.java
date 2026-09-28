package com.github.lisama569.usagebillingenginejava.domain;

/**
 * 账单行：按 (metric, priceVersion) 稳定分组。
 * kind=USAGE 为用量行；kind=ADJUSTMENT 为人工金额调整行。
 */
public record BillLine(
        String kind,
        String metric,
        int priceVersion,
        long unitPriceMinor,
        long quantity,
        long amountMinor
) {
}
