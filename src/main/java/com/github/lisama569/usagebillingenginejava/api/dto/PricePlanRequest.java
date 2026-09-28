package com.github.lisama569.usagebillingenginejava.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 发布价格版本请求。validFrom 必须严格晚于当前版本的生效起点。
 */
public record PricePlanRequest(
        @NotBlank String accountId,
        @NotBlank String metric,
        @NotNull @PositiveOrZero Long unitPrice,
        @NotNull Long validFromEpochMs
) {
}
