package com.github.lisama569.usagebillingenginejava.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 人工纠错请求（仅 ADMIN，账期必须处于 REOPENED）：
 * BACKFILL 补录：eventId 必填新 id，eventTime/quantity/metric 必填；
 * REVERSAL 冲正：eventId 填要冲正的已有事件 id。
 */
public record CorrectionRequest(
        @NotBlank String correctionId,
        @NotBlank String accountId,
        @NotBlank String period,
        @NotBlank String type,
        String eventId,
        String metric,
        @Positive Long quantity,
        Long eventTimeEpochMs,
        @NotBlank String reason
) {
}
