package com.github.lisama569.usagebillingenginejava.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 关账/重开账期请求，period 格式 yyyy-MM。 */
public record PeriodRequest(
        @NotBlank String accountId,
        @NotBlank String period
) {
}
