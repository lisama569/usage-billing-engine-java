package com.github.lisama569.usagebillingenginejava.service;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.domain.OperatorRole;
import com.github.lisama569.usagebillingenginejava.domain.PriceVersion;
import com.github.lisama569.usagebillingenginejava.repository.PriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 价格版本管理。
 *
 * 切换规则（口径）：
 * - 价格版本只追加、不修改：套餐/单价变更 = 发布新版本（effectiveFrom 起生效）；
 * - 一条事件按其"事件时间"匹配当时生效的价格（effectiveFrom <= eventTime 的最新版本），
 *   因此后发布的价格不会回溯改写历史账单，乱序补录结果也与先到先算一致；
 * - 出账时同一 (metric, priceVersion) 聚合成一行，跨切换点的用量会被稳定拆成多行。
 */
@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);

    private final PriceRepository prices;
    private final Clock clock;

    public PriceService(PriceRepository prices, Clock clock) {
        this.prices = prices;
        this.clock = clock;
    }

    public PriceVersion publish(String accountId, String metric, long unitPriceMinor,
                                Instant effectiveFrom, OperatorRole role) {
        if (role != OperatorRole.ADMIN) {
            log.warn("reject price publish account={} metric={} reason=FORBIDDEN role={}", accountId, metric, role);
            throw new BillingException(ErrorCode.FORBIDDEN, "only ADMIN can publish price versions");
        }
        if (accountId == null || accountId.isBlank() || metric == null || metric.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "accountId and metric are required");
        }
        if (unitPriceMinor < 0) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "unitPriceMinor must be >= 0");
        }
        if (effectiveFrom == null) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "effectiveFrom is required");
        }
        PriceVersion stored = prices.append(new PriceVersion(accountId, metric, 0,
                unitPriceMinor, effectiveFrom, Instant.now(clock)));
        log.info("price published account={} metric={} version={} unitPriceMinor={} effectiveFrom={}",
                accountId, metric, stored.version(), unitPriceMinor, effectiveFrom);
        return stored;
    }

    /** 取 at 时刻生效的价格版本；从无可用价格时抛 PRICE_NOT_FOUND。 */
    public PriceVersion activeAt(String accountId, String metric, Instant at) {
        List<PriceVersion> versions = prices.versions(accountId, metric);
        PriceVersion active = null;
        for (PriceVersion v : versions) {
            if (!v.effectiveFrom().isAfter(at) && (active == null || v.effectiveFrom().isAfter(active.effectiveFrom()))) {
                active = v;
            }
        }
        if (active == null) {
            log.warn("price not found account={} metric={} at={} (versions={})", accountId, metric, at, versions.size());
            throw new BillingException(ErrorCode.PRICE_NOT_FOUND,
                    "no effective price for " + accountId + "/" + metric + " at " + at);
        }
        return active;
    }

    public List<PriceVersion> list(String accountId, String metric) {
        return prices.versions(accountId, metric);
    }
}
