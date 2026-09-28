package com.github.lisama569.usagebillingenginejava.api;

import com.github.lisama569.usagebillingenginejava.api.dto.BillView;
import com.github.lisama569.usagebillingenginejava.service.BillingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账单查询（任意角色可查，只读）。 */
@RestController
@RequestMapping("/api/bills")
public class BillController {

    private final BillingService billingService;

    public BillController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping
    public BillView getBill(@RequestParam String accountId, @RequestParam String period) {
        return billingService.getBill(accountId, period);
    }
}
