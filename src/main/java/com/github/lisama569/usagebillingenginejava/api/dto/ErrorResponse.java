package com.github.lisama569.usagebillingenginejava.api.dto;

/** 统一错误响应：错误码与可读原因，调用方据此区分失败类型。 */
public record ErrorResponse(String code, String message) {
}
