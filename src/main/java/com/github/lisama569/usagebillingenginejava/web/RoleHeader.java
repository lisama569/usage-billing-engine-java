package com.github.lisama569.usagebillingenginejava.web;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.domain.OperatorRole;

/** 解析 X-Operator-Role 头：USER（默认）/ ADMIN。 */
final class RoleHeader {

    private RoleHeader() {
    }

    static OperatorRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OperatorRole.USER;
        }
        try {
            return OperatorRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "unknown X-Operator-Role: " + raw);
        }
    }
}
