package com.github.lisama569.usagebillingenginejava.tests;

import com.github.lisama569.usagebillingenginejava.domain.Bill;
import com.github.lisama569.usagebillingenginejava.domain.OperatorRole;
import com.github.lisama569.usagebillingenginejava.service.BillService;
import com.github.lisama569.usagebillingenginejava.service.EventService;
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
 * 套餐/价格切换与跨周期：
 * - 月中切换价格（v1=10 分，v2=20 分自 1/16 生效），事件按各自事件时间定价，
 *   账单按 (metric, priceVersion) 稳定拆成两行；
 * - 之后发布的更贵版本（v3）不回溯历史账单；
 * - 事件落在相邻两个月 => 分别归属各自账期（跨周期）。
 */
@SpringBootTest
class PlanSwitchBillingTest {

    private static final Logger log = LoggerFactory.getLogger(PlanSwitchBillingTest.class);

    @Autowired
    private EventService eventService;
    @Autowired
    private PriceService priceService;
    @Autowired
    private BillService billService;

    @Test
    void priceSwitchSplitsLinesAndDoesNotRewriteHistory() {
        String accountId = "acc-plan-1";
        String metric = "seat";

        var v1 = priceService.publish(accountId, metric, 10, Instant.parse("2026-01-01T00:00:00Z"),
                OperatorRole.ADMIN);
        var v2 = priceService.publish(accountId, metric, 20, Instant.parse("2026-01-16T00:00:00Z"),
                OperatorRole.ADMIN);
        log.info("[输入] 价格 v1=10分(1/1起) v2=20分(1/16起)");

        // 1/10 用 4 个 -> v1；1/20 用 3 个 -> v2
        eventService.ingest("plan-e1", accountId, metric, 4, Instant.parse("2026-01-10T00:00:00Z"),
                OperatorRole.USER);
        eventService.ingest("plan-e2", accountId, metric, 3, Instant.parse("2026-01-20T00:00:00Z"),
                OperatorRole.USER);
        // 跨周期：2/2 的事件归属 2026-02
        eventService.ingest("plan-e3", accountId, metric, 5, Instant.parse("2026-02-02T00:00:00Z"),
                OperatorRole.USER);

        Bill jan = billService.computeDraft(accountId, "2026-01");
        log.info("[判定依据] 1月账单 行数={} 行明细={} totalQty={} totalAmountMinor={}（期望 4*10+3*20=100）",
                jan.lines().size(), jan.lines(), jan.totalQuantity(), jan.totalAmountMinor());
        assertEquals(7, jan.totalQuantity());
        assertEquals(100, jan.totalAmountMinor());
        assertEquals(2, jan.lines().size(), "跨价格版本的用量必须拆成两行");
        assertEquals(List.of(1, 2), jan.lines().stream().map(BillLineVersion()).toList());

        Bill feb = billService.computeDraft(accountId, "2026-02");
        log.info("[判定依据] 2月账单 totalQty={} totalAmountMinor={}（期望 5*20=100，使用 v2）",
                feb.totalQuantity(), feb.totalAmountMinor());
        assertEquals(5, feb.totalQuantity());
        assertEquals(100, feb.totalAmountMinor());
        assertEquals(2, feb.lines().get(0).priceVersion());

        // 2/10 再发布 v3=50 分，历史账期重算金额必须不变（事件时间定价）
        priceService.publish(accountId, metric, 50, Instant.parse("2026-02-10T00:00:00Z"),
                OperatorRole.ADMIN);
        Bill janAgain = billService.computeDraft(accountId, "2026-01");
        log.info("[判定依据] v3 发布后重算1月 totalAmountMinor={}（应仍为100，历史可复现）",
                janAgain.totalAmountMinor());
        assertEquals(100, janAgain.totalAmountMinor());
        assertEquals(jan.lines(), janAgain.lines());

        Bill febAgain = billService.computeDraft(accountId, "2026-02");
        assertEquals(100, febAgain.totalAmountMinor(), "2/2 事件在 v3 生效前，仍按 v2=20 计价");
    }

    private static java.util.function.Function<com.github.lisama569.usagebillingenginejava.domain.BillLine, Integer>
    BillLineVersion() {
        return com.github.lisama569.usagebillingenginejava.domain.BillLine::priceVersion;
    }
}
