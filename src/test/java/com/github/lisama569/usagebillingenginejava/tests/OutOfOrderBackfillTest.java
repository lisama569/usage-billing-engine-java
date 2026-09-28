package com.github.lisama569.usagebillingenginejava.tests;

import com.github.lisama569.usagebillingenginejava.domain.OperatorRole;
import com.github.lisama569.usagebillingenginejava.domain.UsageEvent;
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
 * 乱序补录：事件按"晚到"顺序提交（先交月底、再交月初），
 * 出账必须按事件时间回放，结果与提交顺序无关；重复重算结果稳定一致。
 */
@SpringBootTest
class OutOfOrderBackfillTest {

    private static final Logger log = LoggerFactory.getLogger(OutOfOrderBackfillTest.class);

    @Autowired
    private EventService eventService;
    @Autowired
    private PriceService priceService;
    @Autowired
    private BillService billService;

    @Test
    void lateEventsAreBilledByEventTime_andRecomputeStable() {
        String accountId = "acc-ooo-1";
        String metric = "api_calls";
        // 固定单价 2 分/次，全周期生效
        priceService.publish(accountId, metric, 2, Instant.parse("2026-01-01T00:00:00Z"),
                OperatorRole.ADMIN);

        // 故意乱序提交：31 号 -> 15 号 -> 1 号
        eventService.ingest("ooo-3", accountId, metric, 30, Instant.parse("2026-01-31T23:00:00Z"),
                OperatorRole.USER);
        eventService.ingest("ooo-2", accountId, metric, 20, Instant.parse("2026-01-15T12:00:00Z"),
                OperatorRole.USER);
        eventService.ingest("ooo-1", accountId, metric, 10, Instant.parse("2026-01-01T00:30:00Z"),
                OperatorRole.USER);
        log.info("[输入] 乱序提交 ooo-3(qty30,1/31), ooo-2(qty20,1/15), ooo-1(qty10,1/1)，单价=2分");

        List<UsageEvent> listed = eventService.list(accountId, "2026-01");
        assertEquals(List.of("ooo-1", "ooo-2", "ooo-3"),
                listed.stream().map(UsageEvent::eventId).toList(),
                "查询必须按事件时间稳定排序，与提交顺序无关");
        log.info("[判定依据] 查询顺序={}（按事件时间排序）",
                listed.stream().map(UsageEvent::eventId).toList());

        var bill1 = billService.computeDraft(accountId, "2026-01");
        var bill2 = billService.computeDraft(accountId, "2026-01");
        log.info("[判定依据] 两次重算 totalQty={}/{} totalAmountMinor={}/{}",
                bill1.totalQuantity(), bill2.totalQuantity(),
                bill1.totalAmountMinor(), bill2.totalAmountMinor());
        assertEquals(60, bill1.totalQuantity());
        assertEquals(120, bill1.totalAmountMinor(), "60 次 × 2 分 = 120 分");
        assertEquals(bill1.totalQuantity(), bill2.totalQuantity());
        assertEquals(bill1.totalAmountMinor(), bill2.totalAmountMinor());
        assertEquals(bill1.lines(), bill2.lines(), "草案重算必须逐行一致");

        // 再补录一条月中事件：总量与金额确定性增加
        eventService.ingest("ooo-4", accountId, metric, 5, Instant.parse("2026-01-20T09:00:00Z"),
                OperatorRole.USER);
        var bill3 = billService.computeDraft(accountId, "2026-01");
        log.info("[判定依据] 补录 ooo-4(qty5,1/20) 后 totalQty={} totalAmountMinor={}（应=65/130）",
                bill3.totalQuantity(), bill3.totalAmountMinor());
        assertEquals(65, bill3.totalQuantity());
        assertEquals(130, bill3.totalAmountMinor());
    }
}
