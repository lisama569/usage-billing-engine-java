package com.github.lisama569.usagebillingenginejava.service;

import com.github.lisama569.usagebillingenginejava.domain.Bill;
import com.github.lisama569.usagebillingenginejava.domain.BillLine;
import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.Correction;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.domain.OperatorRole;
import com.github.lisama569.usagebillingenginejava.domain.PeriodState;
import com.github.lisama569.usagebillingenginejava.domain.PriceVersion;
import com.github.lisama569.usagebillingenginejava.domain.UsageEvent;
import com.github.lisama569.usagebillingenginejava.repository.BillRepository;
import com.github.lisama569.usagebillingenginejava.repository.CorrectionRepository;
import com.github.lisama569.usagebillingenginejava.repository.EventRepository;
import com.github.lisama569.usagebillingenginejava.repository.PeriodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 出账、纠错与账单版本管理。
 *
 * 计费口径：
 * 1. 账单 = 账期内全部事件（含 NORMAL 与 CORRECTION）按 (metric, 事件时间生效价格版本)
 *    聚合的用量行 + 金额调整行；事件按 (eventTime,eventId) 稳定排序回放，
 *    故乱序补录、跨价格版本（套餐变更）的结果确定且可复现。
 * 2. 价格按"事件时间"取值：后发布价格不回溯历史事件。
 * 3. 重算/纠错只产生账单新版本，旧版本永久保留；一次纠错在一个新版本内一次性生效，
 *    correctionId 幂等，重试不会重复扣费/重复冲正。
 * 4. 定账(finish)幂等：账期已 CLOSED 且已有 finalized 账单时直接返回该账单。
 */
@Service
public class BillService {

    private static final Logger log = LoggerFactory.getLogger(BillService.class);

    private final EventRepository events;
    private final CorrectionRepository corrections;
    private final BillRepository bills;
    private final PeriodRepository periods;
    private final PriceService priceService;
    private final EventService eventService;
    private final Clock clock;

    public BillService(EventRepository events, CorrectionRepository corrections,
                       BillRepository bills, PeriodRepository periods,
                       PriceService priceService, EventService eventService, Clock clock) {
        this.events = events;
        this.corrections = corrections;
        this.bills = bills;
        this.periods = periods;
        this.priceService = priceService;
        this.eventService = eventService;
        this.clock = clock;
    }

    /** 计算草案（不改变任何状态，可反复调用）。 */
    public Bill computeDraft(String accountId, String period) {
        Bill draft = recompute(accountId, period, false);
        log.info("draft computed account={} period={} totalAmountMinor={} lines={}",
                accountId, period, draft.totalAmountMinor(), draft.lines().size());
        return draft;
    }

    /** 定账：OPEN 账期先关闭再出定账单；CLOSED 账期幂等返回最近定账单。 */
    public Bill finalizeBill(String accountId, String period) {
        Bill[] out = new Bill[1];
        periods.mutate(accountId, period, state -> {
            Bill existingFinalized = latestFinalized(accountId, period);
            if (state.getStatus() == com.github.lisama569.usagebillingenginejava.domain.PeriodStatus.CLOSED
                    && existingFinalized != null) {
                log.info("finalize idempotent account={} period={} billVersion={}",
                        accountId, period, existingFinalized.version());
                out[0] = existingFinalized;
                return null; // 状态无变化
            }
            state.close();
            Bill bill = bills.saveNext(recompute(accountId, period, true));
            out[0] = bill;
            log.info("finalized account={} period={} billVersion={} totalAmountMinor={} acceptedEvents={}",
                    accountId, period, bill.version(), bill.totalAmountMinor(),
                    state.getAcceptedEventIds().size());
            return state;
        });
        return out[0];
    }

    private Bill latestFinalized(String accountId, String period) {
        return bills.versions(accountId, period).stream()
                .filter(Bill::finalized)
                .reduce((a, b) -> b)
                .orElse(null);
    }

    /**
     * 管理员纠错。账期必须处于 CLOSED/REOPENED：
     * - CLOSED：仅允许 AMOUNT_ADJUSTMENT（账期保持关闭，金额调整直接进入新版本）；
     * - REOPENED：允许 USAGE_DELTA（生成 CORRECTION 事件）与 AMOUNT_ADJUSTMENT。
     * 同一 correctionId 重试：内容一致则幂等返回最近账单；内容冲突 => 校验错误。
     */
    public Bill applyCorrection(String correctionId, String accountId, String period,
                                String type, String metric, long signedQuantity,
                                long signedAmountMinor, String reason, String linkedEventId,
                                OperatorRole role) {
        if (role != OperatorRole.ADMIN) {
            log.warn("reject correction id={} account={} period={} reason=FORBIDDEN role={}",
                    correctionId, accountId, period, role);
            throw new BillingException(ErrorCode.FORBIDDEN, "only ADMIN can apply corrections");
        }
        validateCorrectionInput(correctionId, accountId, period, type, metric, reason);
        Correction.Type correctionType = parseType(type);

        Correction existing = corrections.findById(correctionId).orElse(null);
        if (existing != null) {
            if (!sameCorrection(existing, accountId, period, correctionType, metric,
                    signedQuantity, signedAmountMinor, reason, linkedEventId)) {
                log.warn("reject correction id={} reason=VALIDATION_FAILED correctionId reused with different payload",
                        correctionId);
                throw new BillingException(ErrorCode.EVENT_ID_CONFLICT,
                        "correctionId already exists with different payload: " + correctionId);
            }
            Bill latest = getLatest(accountId, period);
            log.info("correction id={} duplicate, idempotent return billVersion={}", correctionId, latest.version());
            return latest;
        }

        Bill[] out = new Bill[1];
        periods.mutate(accountId, period, state -> {
            var status = state.getStatus();
            if (status == com.github.lisama569.usagebillingenginejava.domain.PeriodStatus.OPEN) {
                log.warn("reject correction id={} reason=CORRECTION_NOT_ALLOWED status=OPEN period={} "
                        + "(open periods take normal/backfill events directly)", correctionId, period);
                throw new BillingException(ErrorCode.CORRECTION_NOT_ALLOWED,
                        "period is OPEN; backfill events directly instead of correction");
            }
            if (correctionType == Correction.Type.USAGE_DELTA
                    && status != com.github.lisama569.usagebillingenginejava.domain.PeriodStatus.REOPENED) {
                log.warn("reject correction id={} reason=CORRECTION_NOT_ALLOWED status=CLOSED "
                        + "(USAGE_DELTA requires REOPENED period)", correctionId);
                throw new BillingException(ErrorCode.CORRECTION_NOT_ALLOWED,
                        "usage correction requires reopening the period first");
            }

            Correction correction = new Correction(correctionId, accountId, period, correctionType,
                    metric, signedQuantity, signedAmountMinor, reason, linkedEventId, Instant.now(clock));
            Correction stored = corrections.saveIfAbsent(correction);
            if (stored != correction) {
                // 并发下被另一线程抢先创建，按幂等处理
                out[0] = getLatest(accountId, period);
                return null;
            }

            if (correctionType == Correction.Type.USAGE_DELTA) {
                Instant eventTime = periodEventTime(period);
                String correctionEventId = "corr:" + correctionId;
                eventService.ingestCorrection(correctionEventId, accountId, metric,
                        signedQuantity, eventTime, correctionId);
                log.info("USAGE_DELTA applied correctionId={} eventId={} signedQty={} period={} (status={})",
                        correctionId, correctionEventId, signedQuantity, period, status);
            } else {
                log.info("AMOUNT_ADJUSTMENT applied correctionId={} signedAmountMinor={} period={} (status={})",
                        correctionId, signedAmountMinor, period, status);
            }
            // 是否作为"定账后修正"：此前已有 finalized 账单，则新版本同样标记 finalized
            boolean finalized = latestFinalized(accountId, period) != null;
            Bill bill = bills.saveNext(recompute(accountId, period, finalized));
            out[0] = bill;
            log.info("correction recompute produced billVersion={} account={} period={} totalAmountMinor={} totalQty={}",
                    bill.version(), accountId, period, bill.totalAmountMinor(), bill.totalQuantity());
            return null; // 纠错不改变账期状态（REOPENED 仍待管理员显式关闭）
        });
        return out[0];
    }

    /** 重算：回放账期内事件 + 金额调整纠错单，生成全新不可变账单。 */
    private Bill recompute(String accountId, String period, boolean finalized) {
        List<UsageEvent> periodEvents = events.findByAccountAndPeriod(accountId, period);
        List<Correction> adjustments = corrections.findAmountAdjustments(accountId, period);

        // key = metric|version
        Map<String, UsageAgg> usageAggs = new LinkedHashMap<>();
        int eventCount = 0;
        int correctionEventCount = 0;
        for (UsageEvent e : periodEvents) {
            eventCount++;
            if (e.kind() == com.github.lisama569.usagebillingenginejava.domain.EventKind.CORRECTION) {
                correctionEventCount++;
            }
            PriceVersion price = priceService.activeAt(accountId, e.metric(), e.eventTime());
            String key = e.metric() + "|" + price.version();
            UsageAgg agg = usageAggs.computeIfAbsent(key,
                    k -> new UsageAgg(e.metric(), price.version(), price.unitPriceMinor()));
            agg.quantity += e.quantity();
        }

        // 金额调整行：每条纠错单独立成行，按 correctionId 稳定排序，便于审计与冲正追溯
        List<BillLine> lines = new ArrayList<>();
        long totalQuantity = 0;
        long totalAmount = 0;
        List<UsageAgg> aggList = new ArrayList<>(usageAggs.values());
        aggList.sort(Comparator.comparing((UsageAgg a) -> a.metric).thenComparingInt(a -> a.version));
        for (UsageAgg a : aggList) {
            long amount = Math.multiplyExact(a.quantity, a.unitPrice);
            lines.add(new BillLine("USAGE", a.metric, a.version, a.unitPrice, a.quantity, amount));
            totalQuantity += a.quantity;
            totalAmount += amount;
        }
        List<Correction> sortedAdjustments = new ArrayList<>(adjustments);
        sortedAdjustments.sort(Comparator.comparing(Correction::correctionId));
        for (Correction c : sortedAdjustments) {
            // 每条金额调整单独成行；同 correctionId 金额已天然为一份
            lines.add(new BillLine("ADJUSTMENT", c.metric(), -1, 0, 0, c.signedAmountMinor()));
            totalAmount += c.signedAmountMinor();
        }

        return new Bill(accountId, period, 0, finalized, List.copyOf(lines),
                totalQuantity, totalAmount, eventCount, correctionEventCount, Instant.now(clock));
    }

    private void validateCorrectionInput(String correctionId, String accountId, String period,
                                         String type, String metric, String reason) {
        if (correctionId == null || correctionId.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "correctionId is required");
        }
        if (accountId == null || accountId.isBlank() || period == null || period.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "accountId and period are required");
        }
        if (type == null) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "type is required (USAGE_DELTA|AMOUNT_ADJUSTMENT)");
        }
        if (metric == null || metric.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "metric is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "reason is required for audit");
        }
    }

    private static Correction.Type parseType(String type) {
        try {
            return Correction.Type.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "type must be USAGE_DELTA or AMOUNT_ADJUSTMENT");
        }
    }

    private static boolean sameCorrection(Correction c, String accountId, String period,
                                          Correction.Type type, String metric, long qty,
                                          long amount, String reason, String linked) {
        return c.accountId().equals(accountId) && c.period().equals(period)
                && c.type() == type && c.metric().equals(metric)
                && c.signedQuantity() == qty && c.signedAmountMinor() == amount
                && Objects.equals(c.reason(), reason) && Objects.equals(c.linkedEventId(), linked);
    }

    /** USAGE_DELTA 落到目标账期的固定时间点（该月1日 00:00 UTC），保证重算稳定。 */
    private static Instant periodEventTime(String period) {
        return java.time.LocalDate.parse(period + "-01")
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    public Bill getLatest(String accountId, String period) {
        return bills.findLatest(accountId, period)
                .orElseThrow(() -> new BillingException(ErrorCode.BILL_NOT_FOUND,
                        "no bill for " + accountId + "/" + period));
    }

    public Bill getVersion(String accountId, String period, int version) {
        return bills.findVersion(accountId, period, version)
                .orElseThrow(() -> new BillingException(ErrorCode.BILL_NOT_FOUND,
                        "bill version not found: " + accountId + "/" + period + "#v" + version));
    }

    public List<Bill> versions(String accountId, String period) {
        return bills.versions(accountId, period);
    }

    private static final class UsageAgg {
        final String metric;
        final int version;
        final long unitPrice;
        long quantity;

        UsageAgg(String metric, int version, long unitPrice) {
            this.metric = metric;
            this.version = version;
            this.unitPrice = unitPrice;
        }
    }
}
