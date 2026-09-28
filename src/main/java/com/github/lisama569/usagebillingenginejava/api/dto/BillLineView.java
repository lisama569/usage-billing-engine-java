package com.github.lisama569.usagebillingenginejava.api.dto;

/**
 * 账单明细行。每个未冲正事件按 eventTime 命中的价格版本计价；
 * reversed=true 的冲正事件单独列出（lineAmount=0）便于核对总量守恒。
 */
public record BillLineView(
        String eventId,
        String metric,
        long quantity,
        long eventTimeEpochMs,
        Long unitPrice,
        long lineAmount,
        boolean reversed,
        String correctionId
) {
}
