package com.github.lisama569.usagebillingenginejava.domain;

/** 所有业务错误统一异常，携带可区分的 {@link ErrorCode}。 */
public class BillingException extends RuntimeException {
    private final ErrorCode errorCode;

    public BillingException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
