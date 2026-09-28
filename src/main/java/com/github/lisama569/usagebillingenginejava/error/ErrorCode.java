package com.github.lisama569.usagebillingenginejava.error;

/**
 * 业务错误码。HTTP 状态与错误原因一一对应且可区分：
 * 422 校验失败；409 冲突（幂等/账期/状态/价格）；403 权限不足；404 不存在。
 */
public enum ErrorCode {

    VALIDATION_FAILED(422),
    EVENT_ID_CONFLICT(409),
    PERIOD_CLOSED(409),
    PERIOD_STATUS_CONFLICT(409),
    CORRECTION_CONFLICT(409),
    PRICE_NOT_DEFINED(409),
    PRICE_PLAN_CONFLICT(409),
    FORBIDDEN(403),
    NOT_FOUND(404);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
