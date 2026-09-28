package com.github.lisama569.usagebillingenginejava.tests;

import com.github.lisama569.usagebillingenginejava.domain.Bill;
import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.domain.OperatorRole;
import com.github.lisama569.usagebillingenginejava.service.BillService;
import com.github.lisama569.usagebillingenginejava.service.EventService;
import com.github.lisama569.usagebillingenginejava.service.PeriodService;
import com.github.lisama569.usagebillingenginejava.service.PriceService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纠错与重算：
 * 1. 定账后对 CLOSED 账期可直接做 AMOUNT_ADJUSTMENT（保持关闭）；
 * 2. USAGE_DELTA 必须先受控重开，且只有 ADMIN 可操作；
 * 3. 同一 correctionId 重试不会重复生效（不重复扣费/冲正），冲突内容可区分；
 * 4. 账单版本只增不覆盖，历史版本逐行可复现；冲正后总量守恒。
 */
@SpringBootTest
class CorrectionRecomputeTest {

    private static final Logger log = LoggerFactory.getLogger(CorrectionRecomputeTest.class);

    @Autowired
    private EventService eventService;
    @Autowired
    private PriceService priceService;
    @Autowired
    private PeriodService periodService;
    @Autowired
    private BillService billService;

    @Test
    void correctionLifecycle_isIdempotentConservativeAndReproducible() {
        String accountId = "acc-corr-1";
        String period = "2026-06";
        String metric = "storage_gb";
        priceService.publish(accountId, metric, 3, Instant.parse("2026-01-01T00:00:00Z"),
                OperatorRole.ADMIN);
        eventService.ingest("cr-1", accountId, metric, 100, Instant.parse("2026-06-05T00:00:00Z"),
                OperatorRole.USER);

        // 定账：100 × 3 = 300
        Bill v1 = billService.finalizeBill(accountId, period);
        log.info("[输入/判定依据] 定账 v1 totalAmountMinor={} finalized={}（期望300,true）",
                v1.totalAmountMinor(), v1.finalized());
        assertEquals(300, v1.totalAmountMinor());
        assertTrue(v1.finalized());
        assertEquals(1, v1.version());

        // 重复定账幂等：不产生新版本
        Bill v1again = billService.finalizeBill(accountId, period);
        assertEquals(1, v1again.version());
        log.info("[判定依据] 重复定账返回版本={}（应仍为1，幂等）", v1again.version());

        // 普通用户无权纠错 => FORBIDDEN
        BillingException forbidden = assertThrows(BillingException.class, () ->
                billService.applyCorrection("x1", accountId, period, "AMOUNT_ADJUSTMENT",
                        metric, 0, -50, "goodwill refund", null, OperatorRole.USER));
        assertEquals(ErrorCode.FORBIDDEN, forbidden.getErrorCode());
        log.info("[判定依据] USER 纠错 errorCode=FORBIDDEN");

        // CLOSED 账期做金额调整 -50：直接产生 v2，账期仍 CLOSED
        Bill v2 = billService.applyCorrection("corr-adj-1", accountId, period,
                "AMOUNT_ADJUSTMENT", metric, 0, -50, "goodwill refund", null, OperatorRole.ADMIN);
        log.info("[判定依据] 金额调整-50后 v2 totalAmountMinor={} 行数={}（期望250,2：用量行+调整行）",
                v2.totalAmountMinor(), v2.lines().size());
        assertEquals(250, v2.totalAmountMinor());
        assertEquals(2, v2.lines().size());
        assertEquals("ADJUSTMENT", v2.lines().get(1).kind());

        // 同一 correctionId 重试：不重复冲正，金额不变
        Bill v2retry = billService.applyCorrection("corr-adj-1", accountId, period,
                "AMOUNT_ADJUSTMENT", metric, 0, -50, "goodwill refund", null, OperatorRole.ADMIN);
        log.info("[判定依据] 同单重试 totalAmountMinor={} 版本={}（期望250,2，无新版本）",
                v2retry.totalAmountMinor(), v2retry.version());
        assertEquals(250, v2retry.totalAmountMinor());
        assertEquals(2, v2retry.version());

        // 同 id 换金额 => 冲突可区分
        BillingException conflict = assertThrows(BillingException.class, () ->
                billService.applyCorrection("corr-adj-1", accountId, period,
                        "AMOUNT_ADJUSTMENT", metric, 0, -999, "changed", null, OperatorRole.ADMIN));
        assertEquals(ErrorCode.EVENT_ID_CONFLICT, conflict.getErrorCode());

        // CLOSED 账期不允许 USAGE_DELTA => CORRECTION_NOT_ALLOWED(422)
        BillingException notAllowed = assertThrows(BillingException.class, () ->
                billService.applyCorrection("corr-u1", accountId, period, "USAGE_DELTA",
                        metric, 10, 0, "backfill", null, OperatorRole.ADMIN));
        assertEquals(ErrorCode.CORRECTION_NOT_ALLOWED, notAllowed.getErrorCode());
        log.info("[判定依据] CLOSED 账期 USAGE_DELTA errorCode=CORRECTION_NOT_ALLOWED");

        // 受控重开后做用量冲正：-20（多计了20），金额 -60 => 190
        periodService.reopen(accountId, period, "发现 6 月重复计量 20 单位");
        Bill v3 = billService.applyCorrection("corr-u2", accountId, period, "USAGE_DELTA",
                metric, -20, 0, "reverse duplicated metering", "cr-1", OperatorRole.ADMIN);
        log.info("[判定依据] 用量冲正-20后 v3 totalQty={} totalAmountMinor={} USAGE行={}（期望80/190；冲正与原用量同价聚合为1行+1调整行）",
                v3.totalQuantity(), v3.totalAmountMinor(),
                v3.lines().stream().filter(l -> l.kind().equals("USAGE")).count());
        assertEquals(80, v3.totalQuantity());
        assertEquals(190, v3.totalAmountMinor());
        assertEquals(1, v3.correctionCount());
        assertEquals(2, v3.eventCount(), "原始事件+纠错事件");
        // 冲正用量与原始用量在同一价格版本下聚合：USAGE 行数量净值 80
        var usageLine = v3.lines().stream().filter(l -> l.kind().equals("USAGE")).findFirst().orElseThrow();
        assertEquals(80, usageLine.quantity());
        assertEquals(240, usageLine.amountMinor());

        // 用量纠错重试：不重复冲正
        Bill v3retry = billService.applyCorrection("corr-u2", accountId, period, "USAGE_DELTA",
                metric, -20, 0, "reverse duplicated metering", "cr-1", OperatorRole.ADMIN);
        log.info("[判定依据] 用量冲正重试 totalQty={} totalAmountMinor={} 版本={}（期望80/190/3）",
                v3retry.totalQuantity(), v3retry.totalAmountMinor(), v3retry.version());
        assertEquals(80, v3retry.totalQuantity());
        assertEquals(190, v3retry.totalAmountMinor());
        assertEquals(3, v3retry.version());

        // 总量守恒：当前事件代数和 100-20=80；历史版本仍可复现
        List<Bill> versions = billService.versions(accountId, period);
        log.info("[判定依据] 版本列表={}", versions.stream().map(b -> "v" + b.version() + "="
                + b.totalAmountMinor()).toList());
        assertEquals(3, versions.size());
        assertEquals(300, versions.get(0).totalAmountMinor());
        assertEquals(250, versions.get(1).totalAmountMinor());
        assertEquals(190, versions.get(2).totalAmountMinor());
        Bill histV1 = billService.getVersion(accountId, period, 1);
        assertEquals(300, histV1.totalAmountMinor());
        assertEquals(100, histV1.totalQuantity());
        assertEquals(1, histV1.lines().size(), "历史版本 v1 内容不被后续重算改写");

        // 补录正向用量 +20：80 -> 100，190 -> 250
        Bill v4 = billService.applyCorrection("corr-u3", accountId, period, "USAGE_DELTA",
                metric, 20, 0, "backfill missed metering", null, OperatorRole.ADMIN);
        log.info("[判定依据] 正向补录+20后 v4 totalQty={} totalAmountMinor={}（期望100/250）",
                v4.totalQuantity(), v4.totalAmountMinor());
        assertEquals(100, v4.totalQuantity());
        assertEquals(250, v4.totalAmountMinor());
    }
}
