package com.github.lisama569.usagebillingenginejava.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 用量事件写入请求。eventId 为调用方提供的全局唯一幂等键。
 */
public record UsageEventRequest(
        @NotBlank String eventId,
        @NotBlank String accountId,
        @NotBlank String metric,
        @NotNull @Positive Long quantity,
        @NotNull Long eventTimeEpochMs
) {
}
