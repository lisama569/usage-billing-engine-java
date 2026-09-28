package com.github.lisama569.usagebillingenginejava.domain;

/** 计费引擎可区分的错误类别。 */
public enum ErrorCode {
    VALIDATION_FAILED,
    EVENT_ID_CONFLICT,
    PERIOD_CLOSED,
    PERIOD_STATE_CONFLICT,
    FORBIDDEN,
    CORRECTION_NOT_ALLOWED,
    PRICE_NOT_FOUND,
    EVENT_NOT_FOUND,
    BILL_NOT_FOUND,
    CORRECTION_NOT_FOUND
}
