package com.github.lisama569.usagebillingenginejava.web;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.web.dto.ApiDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务错误 -> 可区分的 HTTP 状态与错误码：
 * 400 VALIDATION_FAILED / 403 FORBIDDEN / 404 *_NOT_FOUND /
 * 409 EVENT_ID_CONFLICT, PERIOD_STATE_CONFLICT / 422 CORRECTION_NOT_ALLOWED /
 * 423 PERIOD_CLOSED。
 */
@RestControllerAdvice
public class BillingExceptionHandler {

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handle(BillingException ex) {
        return ResponseEntity.status(status(ex.getErrorCode()))
                .body(new ApiDtos.ErrorResponse(ex.getErrorCode().name(), ex.getMessage()));
    }

    private HttpStatus status(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case EVENT_NOT_FOUND, BILL_NOT_FOUND, CORRECTION_NOT_FOUND, PRICE_NOT_FOUND ->
                    HttpStatus.NOT_FOUND;
            case EVENT_ID_CONFLICT, PERIOD_STATE_CONFLICT -> HttpStatus.CONFLICT;
            case CORRECTION_NOT_ALLOWED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PERIOD_CLOSED -> HttpStatus.LOCKED;
        };
    }
}
