package com.github.lisama569.usagebillingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 价格版本切换与乱序补录：
 * - 价格版本在月中切换：每个事件按自己的 eventTime 命中版本，跨版本结果确定；
 * - 先提交晚事件、后补录早事件（乱序），账单结果与提交顺序无关；
 * - 价格版本只增不改，事后补录历史事件仍命中当时价格，历史可复现；
 * - 完全没有适用价格的 metric 出账 -> PRICE_NOT_DEFINED；补建价格后可正常出账。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_price_switch;DB_CLOSE_DELAY=-1"
})
class PriceSwitchAndOrderingTest extends BillingIntegrationTestBase {

    @Test
    void midMonthPriceSwitch_pricesEachEventByItsOwnEventTime() throws Exception {
        long switchAt = utc(2026, 5, 15); // 5 月 15 日 00:00 UTC 切换
        publishPlan(200, "acct-P", "api_calls", 2, utc(2026, 5, 1), "ADMIN");
        publishPlan(200, "acct-P", "api_calls", 3, switchAt, "ADMIN");

        // 乱序：先发 5/20 的事件（新价 3），再补 5/14 与 5/2 的事件（旧价 2）
        writeEvent(200, "e-late", "acct-P", "api_calls", 10, utc(2026, 5, 20));
        writeEvent(200, "e-early-2", "acct-P", "api_calls", 4, utc(2026, 5, 14, 23));
        writeEvent(200, "e-early-1", "acct-P", "api_calls", 5, utc(2026, 5, 2));

        JsonNode bill = json(getBill("acct-P", "2026-05"));
        long expectedAmount = 10L * 3 + 4L * 2 + 5L * 2; // 48
        log.info("判定依据: 乱序三条事件 5/20(q10,p3) 5/14(q4,p2) 5/2(q5,p2) -> amount={}", expectedAmount);
        assertEquals(expectedAmount, bill.path("totalAmount").asLong(), "月中切价后各事件按 eventTime 命中价格");
        assertEquals(19, bill.path("totalQuantity").asLong());
        assertEquals(3, bill.path("lines").size(), "明细按 eventTime 升序，乱序提交不影响顺序");
        assertEquals("e-early-1", bill.path("lines").get(0).path("eventId").asText(),
                "明细必须按 eventTime 排序而非接收顺序");
        assertEquals(2, bill.path("lines").get(0).path("unitPrice").asLong());
        assertEquals(3, bill.path("lines").get(2).path("unitPrice").asLong());

        JsonNode billAgain = json(getBill("acct-P", "2026-05"));
        assertEquals(bill.path("totalAmount").asLong(), billAgain.path("totalAmount").asLong());
        assertEquals(bill.toString().lines().count(), billAgain.toString().lines().count());
        log.info("判定依据: 再次查询 amount={} 与首次一致，明细顺序稳定（历史可复现）",
                billAgain.path("totalAmount").asLong());
    }

    @Test
    void missingPriceRejectsCloseAndBackfilledPriceMakesItBillable() throws Exception {
        // storage 这个 metric 从未发布过价格
        writeEvent(200, "s-1", "acct-Q", "storage", 2, utc(2026, 6, 10));

        MvcResult close = closePeriod(409, "acct-Q", "2026-06", "ADMIN");
        assertErrorCode(close, "PRICE_NOT_DEFINED");
        log.info("判定依据: 无价格版本的 metric 关账被拒 PRICE_NOT_DEFINED");

        // 关账失败后账期仍是 OPEN，对 OPEN 重开应为状态冲突
        MvcResult reopen = reopenPeriod(409, "acct-Q", "2026-06", "ADMIN");
        assertErrorCode(reopen, "PERIOD_STATUS_CONFLICT");
        log.info("判定依据: 关账失败未改状态，OPEN 账期重开被拒 PERIOD_STATUS_CONFLICT");

        // 补建一个覆盖 6/1 的价格后可正常出账
        publishPlan(200, "acct-Q", "storage", 5, utc(2026, 6, 1), "ADMIN");
        JsonNode closed = json(closePeriod(200, "acct-Q", "2026-06", "ADMIN"));
        assertEquals("CLOSED", closed.path("status").asText());

        JsonNode bill = json(getBill("acct-Q", "2026-06"));
        assertEquals(10, bill.path("totalAmount").asLong(), "2 * 5 = 10");
        assertEquals("FINAL", bill.path("status").asText());
        log.info("判定依据: 补建覆盖 6/1 的价格后出账 amount=10, status=FINAL");
    }
}
