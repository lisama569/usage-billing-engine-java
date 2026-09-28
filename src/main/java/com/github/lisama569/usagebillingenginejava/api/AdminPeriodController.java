package com.github.lisama569.usagebillingenginejava.api;

import com.github.lisama569.usagebillingenginejava.api.dto.BillView;
import com.github.lisama569.usagebillingenginejava.api.dto.PeriodRequest;
import com.github.lisama569.usagebillingenginejava.api.dto.PeriodStatusView;
import com.github.lisama569.usagebillingenginejava.service.BillingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账期关账/重开/重算（ADMIN）。 */
@RestController
@RequestMapping("/api/admin/periods")
public class AdminPeriodController {

    private final BillingService billingService;

    public AdminPeriodController(BillingService billingService) {
        this.billingService = billingService;
    }

    @PostMapping("/close")
    public PeriodStatusView close(@Valid @RequestBody PeriodRequest request, HttpServletRequest http) {
        return billingService.closePeriod(request.accountId(), request.period(),
                (String) http.getAttribute("operatorId"));
    }

    @PostMapping("/reopen")
    public PeriodStatusView reopen(@Valid @RequestBody PeriodRequest request, HttpServletRequest http) {
        return billingService.reopenPeriod(request.accountId(), request.period(),
                (String) http.getAttribute("operatorId"));
    }

    @PostMapping("/recompute")
    public BillView recompute(@Valid @RequestBody PeriodRequest request) {
        return billingService.recompute(request.accountId(), request.period());
    }

    @GetMapping
    public PeriodStatusView status(@RequestParam String accountId, @RequestParam String period) {
        return billingService.getStatus(accountId, period);
    }
}
