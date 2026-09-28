package com.github.lisama569.usagebillingenginejava.security;

import com.github.lisama569.usagebillingenginejava.error.BillingException;
import com.github.lisama569.usagebillingenginejava.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理类接口（/api/admin/**）鉴权：要求请求头 X-Operator-Role=ADMIN。
 * 权限不足抛 BillingException(FORBIDDEN, 403)，与 409 状态冲突、422 校验失败区分。
 */
@Component
public class AdminSecurityInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityInterceptor.class);

    public static final String HEADER_ROLE = "X-Operator-Role";
    public static final String HEADER_OPERATOR = "X-Operator-Id";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String DEFAULT_OPERATOR = "anonymous";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String role = request.getHeader(HEADER_ROLE);
        String operator = request.getHeader(HEADER_OPERATOR);
        String operatorId = (operator == null || operator.isBlank()) ? DEFAULT_OPERATOR : operator;
        boolean allowed = ROLE_ADMIN.equalsIgnoreCase(role);
        log.info("鉴权判定 path={}, operator={}, role={}, allowed={}",
                request.getRequestURI(), operatorId, role, allowed);
        if (!allowed) {
            throw new BillingException(ErrorCode.FORBIDDEN,
                    "管理操作需要 X-Operator-Role=ADMIN，实际 role=" + role);
        }
        request.setAttribute("operatorId", operatorId);
        return true;
    }
}
