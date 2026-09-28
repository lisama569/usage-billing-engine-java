package com.github.lisama569.usagebillingenginejava.service;

import com.github.lisama569.usagebillingenginejava.api.dto.PricePlanRequest;
import com.github.lisama569.usagebillingenginejava.domain.PricePlanVersion;
import com.github.lisama569.usagebillingenginejava.error.BillingException;
import com.github.lisama569.usagebillingenginejava.error.ErrorCode;
import com.github.lisama569.usagebillingenginejava.repo.PricePlanVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricePlanService {

    private static final Logger log = LoggerFactory.getLogger(PricePlanService.class);

    private final PricePlanVersionRepository planRepo;

    public PricePlanService(PricePlanVersionRepository planRepo) {
        this.planRepo = planRepo;
    }

    /**
     * 发布价格版本。规则：
     * - 版本一旦写入不可修改（历史可复现）；
     * - 若存在当前版本，新 validFrom 必须严格晚于它的 validFrom，旧版本 validUntil
     *   被闭合为新 validFrom，版本链首尾相接、无空隙无重叠；
     * - 发布动作加当前版本行锁串行化，避免两个新版本并发互相覆盖。
     */
    @Transactional
    public void publish(PricePlanRequest req, String operator) {
        log.info("发布价格版本 account={}, metric={}, unitPrice={}, validFrom={}, operator={}",
                req.accountId(), req.metric(), req.unitPrice(), req.validFromEpochMs(), operator);

        PricePlanVersion current =
                planRepo.lockCurrentVersion(req.accountId(), req.metric()).orElse(null);
        if (current != null && req.validFromEpochMs() <= current.getValidFrom()) {
            log.warn("价格版本冲突: 已存在 validFrom={}，新 validFrom={} 未严格递增，判定=PRICE_PLAN_CONFLICT",
                    current.getValidFrom(), req.validFromEpochMs());
            throw new BillingException(ErrorCode.PRICE_PLAN_CONFLICT,
                    "新价格版本生效时间必须晚于当前版本生效时间 " + current.getValidFrom());
        }

        PricePlanVersion next = new PricePlanVersion();
        next.setAccountId(req.accountId());
        next.setMetric(req.metric());
        next.setUnitPrice(req.unitPrice());
        next.setValidFrom(req.validFromEpochMs());
        next.setValidUntil(null);
        next.setCreatedBy(operator);
        next.setCreatedAt(System.currentTimeMillis());
        if (current != null) {
            current.setValidUntil(req.validFromEpochMs());
            planRepo.save(current);
            log.info("旧价格版本闭合 id={}, validUntil={}", current.getId(), req.validFromEpochMs());
        }
        planRepo.save(next);
        log.info("价格版本已发布 id={}, account={}, metric={}, validFrom={}",
                next.getId(), req.accountId(), req.metric(), req.validFromEpochMs());
    }
}
