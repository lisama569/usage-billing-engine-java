package com.github.lisama569.usagebillingenginejava.api;

import com.github.lisama569.usagebillingenginejava.api.dto.PricePlanRequest;
import com.github.lisama569.usagebillingenginejava.security.AdminSecurityInterceptor;
import com.github.lisama569.usagebillingenginejava.service.PricePlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 价格版本发布（ADMIN）。 */
@RestController
@RequestMapping("/api/admin/plans")
public class AdminPricePlanController {

    private final PricePlanService pricePlanService;

    public AdminPricePlanController(PricePlanService pricePlanService) {
        this.pricePlanService = pricePlanService;
    }

    @PostMapping
    public Map<String, Object> publish(@Valid @RequestBody PricePlanRequest request,
                                       HttpServletRequest http) {
        String operator = (String) http.getAttribute("operatorId");
        pricePlanService.publish(request, operator);
        return Map.of("accepted", true,
                "accountId", request.accountId(),
                "metric", request.metric(),
                "validFromEpochMs", request.validFromEpochMs());
    }
}
