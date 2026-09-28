package com.github.lisama569.usagebillingenginejava.error;

/**
 * 业务异常：错误码 + 可读原因，原因串会原样返回给调用方并写入日志，
 * 保证 EVENT_ID_CONFLICT 与 PERIOD_CLOSED 等失败原因可区分。
 */
public class BillingException extends RuntimeException {

    private final ErrorCode code;

    public BillingException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
