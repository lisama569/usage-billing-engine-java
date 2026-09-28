package com.github.lisama569.usagebillingenginejava.web;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.domain.PeriodState;
import com.github.lisama569.usagebillingenginejava.service.PeriodService;
import com.github.lisama569.usagebillingenginejava.web.dto.ApiDtos;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 账期：查询 / 关闭 / 受控重开（均为 ADMIN 操作；权限在控制器层拦截，返回 403）。 */
@RestController
@RequestMapping("/api/periods")
public class PeriodController {

    private final PeriodService periodService;

    public PeriodController(PeriodService periodService) {
        this.periodService = periodService;
    }

    @GetMapping
    public Map<String, Object> view(@RequestParam String accountId, @RequestParam String period) {
        PeriodState s = periodService.view(accountId, period);
        return Map.of(
                "accountId", s.getAccountId(),
                "period", s.getPeriod(),
                "status", s.getStatus().name(),
                "revision", s.getRevision(),
                "reopenCount", s.getReopenCount(),
                "reopenReason", s.getReopenReason() == null ? "" : s.getReopenReason(),
                "acceptedEventCount", s.getAcceptedEventIds().size());
    }

    @PostMapping("/close")
    public Object close(@RequestHeader(value = "X-Operator-Role", defaultValue = "USER") String role,
                        @RequestParam String accountId, @RequestParam String period) {
        requireAdmin(role);
        return periodService.close(accountId, period);
    }

    @PostMapping("/reopen")
    public Object reopen(@RequestHeader(value = "X-Operator-Role", defaultValue = "USER") String role,
                         @RequestParam String accountId, @RequestParam String period,
                         @RequestBody(required = false) ApiDtos.ReopenRequest req) {
        requireAdmin(role);
        String reason = req == null ? null : req.reason();
        return periodService.reopen(accountId, period, reason);
    }

    private void requireAdmin(String role) {
        if (RoleHeader.parse(role) != com.github.lisama569.usagebillingenginejava.domain.OperatorRole.ADMIN) {
            throw new BillingException(ErrorCode.FORBIDDEN,
                    "admin role required for this operation");
        }
    }
}
