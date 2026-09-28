package com.github.lisama569.usagebillingenginejava.api;

import com.github.lisama569.usagebillingenginejava.api.dto.BillView;
import com.github.lisama569.usagebillingenginejava.api.dto.CorrectionRequest;
import com.github.lisama569.usagebillingenginejava.service.BillingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 人工纠错（ADMIN；账期必须处于 REOPENED）。 */
@RestController
@RequestMapping("/api/admin/corrections")
public class AdminCorrectionController {

    private final BillingService billingService;

    public AdminCorrectionController(BillingService billingService) {
        this.billingService = billingService;
    }

    @PostMapping
    public BillView correct(@Valid @RequestBody CorrectionRequest request, HttpServletRequest http) {
        return billingService.applyCorrection(request, (String) http.getAttribute("operatorId"));
    }
}
