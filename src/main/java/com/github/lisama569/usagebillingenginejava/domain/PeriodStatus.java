package com.github.lisama569.usagebillingenginejava.domain;

/**
 * 账期状态流转：OPEN -> CLOSED -> REOPENED -> CLOSED。
 * 普通写入仅在 OPEN 被接受；REOPENED 仅接受管理员纠错写入。
 */
public enum PeriodStatus {
    OPEN,
    CLOSED,
    REOPENED
}
