package com.github.lisama569.usagebillingenginejava.web;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.service.PriceService;
import com.github.lisama569.usagebillingenginejava.web.dto.ApiDtos;
import org.springframework.web.bind.annotation.*;

/** 价格版本：发布（管理员）/ 查询。 */
@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceService priceService;

    public PriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @PostMapping
    public Object publish(@RequestHeader(value = "X-Operator-Role", defaultValue = "USER") String role,
                          @RequestBody ApiDtos.PublishPriceRequest req) {
        if (req == null || req.unitPriceMinor() == null || req.effectiveFrom() == null) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "unitPriceMinor and effectiveFrom are required");
        }
        return priceService.publish(req.accountId(), req.metric(), req.unitPriceMinor(),
                req.effectiveFrom(), RoleHeader.parse(role));
    }

    @GetMapping
    public Object list(@RequestParam String accountId, @RequestParam String metric) {
        return priceService.list(accountId, metric);
    }
}
