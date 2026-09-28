package com.github.lisama569.usagebillingenginejava.api.dto;

/**
 * 写入结果。idempotentReplay=true 表示命中幂等、本次并未重复累计。
 */
public record WriteResult(
        String eventId,
        String period,
        boolean accepted,
        boolean idempotentReplay
) {
}
