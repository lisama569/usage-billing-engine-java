package com.github.lisama569.usagebillingenginejava.domain;

/**
 * 账期状态：OPEN 可正常写入；CLOSED 已出账关闭，拒绝一切写入；
 * REOPENED 受控重开，仅允许带 ADMIN 权限的纠错写入。
 */
public enum BillingPeriodStatus {
    OPEN,
    CLOSED,
    REOPENED
}
