package com.github.lisama569.usagebillingenginejava.api.dto;

/** 账期状态视图。 */
public record PeriodStatusView(
        String accountId,
        String period,
        String status,
        int reopenCount
) {
}
