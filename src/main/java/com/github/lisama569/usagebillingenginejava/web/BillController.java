package com.github.lisama569.usagebillingenginejava.web;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.service.BillService;
import com.github.lisama569.usagebillingenginejava.web.dto.ApiDtos;
import org.springframework.web.bind.annotation.*;

/**
 * 账单：出账草案 / 定账 / 纠错（管理员）/ 版本查询。
 * 草案与查询任何角色可用；定账、纠错要求 ADMIN。
 */
@RestController
@RequestMapping("/api/bills")
public class BillController {

    private final BillService billService;

    public BillController(BillService billService) {
        this.billService = billService;
    }

    @PostMapping("/draft")
    public Object draft(@RequestParam String accountId, @RequestParam String period) {
        return billService.computeDraft(accountId, period);
    }

    @PostMapping("/finalize")
    public Object finalize(@RequestHeader(value = "X-Operator-Role", defaultValue = "USER") String role,
                           @RequestParam String accountId, @RequestParam String period) {
        requireAdmin(role);
        return billService.finalizeBill(accountId, period);
    }

    @PostMapping("/corrections")
    public Object correct(@RequestHeader(value = "X-Operator-Role", defaultValue = "USER") String role,
                          @RequestParam String accountId, @RequestParam String period,
                          @RequestBody ApiDtos.CorrectionRequest req) {
        requireAdmin(role);
        if (req == null || req.correctionId() == null || req.type() == null
                || req.signedQuantity() == null || req.signedAmountMinor() == null) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "correctionId, type, signedQuantity, signedAmountMinor are required");
        }
        return billService.applyCorrection(req.correctionId(), accountId, period, req.type(),
                req.metric(), req.signedQuantity(), req.signedAmountMinor(), req.reason(),
                req.linkedEventId(), RoleHeader.parse(role));
    }

    @GetMapping
    public Object get(@RequestParam String accountId, @RequestParam String period,
                      @RequestParam(required = false) Integer version) {
        return version != null
                ? billService.getVersion(accountId, period, version)
                : billService.getLatest(accountId, period);
    }

    @GetMapping("/versions")
    public Object versions(@RequestParam String accountId, @RequestParam String period) {
        return billService.versions(accountId, period);
    }

    private void requireAdmin(String role) {
        if (RoleHeader.parse(role) != com.github.lisama569.usagebillingenginejava.domain.OperatorRole.ADMIN) {
            throw new BillingException(ErrorCode.FORBIDDEN, "admin role required for this operation");
        }
    }
}
