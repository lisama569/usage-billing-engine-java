package com.github.lisama569.usagebillingenginejava.api;

import com.github.lisama569.usagebillingenginejava.api.dto.ErrorResponse;
import com.github.lisama569.usagebillingenginejava.error.BillingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<ErrorResponse> handleBilling(BillingException ex) {
        log.warn("业务拒绝 code={}, reason={}", ex.getCode(), ex.getMessage());
        ErrorResponse body = new ErrorResponse(ex.getCode().name(), ex.getMessage());
        return ResponseEntity.status(ex.getCode().getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        log.warn("参数校验失败 detail={}", detail);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("VALIDATION_FAILED", detail));
    }

    /** 乐观锁冲突：账期状态已被并发事务改变，调用方应重新读取后重试 */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("并发状态冲突 {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("PERIOD_STATUS_CONFLICT", "账期状态已被并发操作修改，请重试"));
    }
}
