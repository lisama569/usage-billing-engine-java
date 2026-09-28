package com.github.lisama569.usagebillingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 跨周期：账期归属只由 eventTime 的 UTC 自然月决定，与接收时间/提交顺序无关。
 * 月末 23:59 与次月 00:01 的事件分属不同账期；全部事件在“次月”一次性乱序补录，
 * 各账期金额仍然确定。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_cross_period;DB_CLOSE_DELAY=-1"
})
class CrossPeriodTest extends BillingIntegrationTestBase {

    @Test
    void eventsAreAttributedByEventTimeMonthRegardlessOfArrivalOrder() throws Exception {
        // 价格 10 长期有效
        publishPlan(200, "acct-M", "api_calls", 10, utc(2026, 1, 1), "ADMIN");

        // 全部在 3 月才提交，时间却横跨 1/2/3 月
        writeEvent(200, "x-jan", "acct-M", "api_calls", 3, utc(2026, 1, 31, 23));
        writeEvent(200, "x-feb", "acct-M", "api_calls", 5, utc(2026, 2, 1, 0));
        writeEvent(200, "x-mar", "acct-M", "api_calls", 7, utc(2026, 3, 15));

        JsonNode jan = json(getBill("acct-M", "2026-01"));
        JsonNode feb = json(getBill("acct-M", "2026-02"));
        JsonNode mar = json(getBill("acct-M", "2026-03"));

        log.info("判定依据: 跨月乱序补录 -> 1月 amount={}, 2月 amount={}, 3月 amount={}",
                jan.path("totalAmount").asLong(), feb.path("totalAmount").asLong(), mar.path("totalAmount").asLong());
        assertEquals(30, jan.path("totalAmount").asLong(), "1月: 3*10");
        assertEquals(50, feb.path("totalAmount").asLong(), "2月: 5*10");
        assertEquals(70, mar.path("totalAmount").asLong(), "3月: 7*10");

        // 各账期事件数互不串账
        MvcResultHelper.events(this, "acct-M", "2026-01", 1);
        MvcResultHelper.events(this, "acct-M", "2026-02", 1);
        MvcResultHelper.events(this, "acct-M", "2026-03", 1);

        // 关闭 2 月后，补录一条 eventTime 在 2 月的事件 -> PERIOD_CLOSED；3 月事件不受影响
        JsonNode closed = json(closePeriod(200, "acct-M", "2026-02", "ADMIN"));
        assertEquals("CLOSED", closed.path("status").asText());
        MvcResultHelper.denied(this);
        writeEvent(200, "x-mar-2", "acct-M", "api_calls", 1, utc(2026, 3, 16));
        JsonNode marAfter = json(getBill("acct-M", "2026-03"));
        assertEquals(80, marAfter.path("totalAmount").asLong(), "3月开放可继续写，2月关闭互不影响");
        log.info("判定依据: 关 2 月不影响 3 月继续累计，3月 amount=80");
    }

    /** 小工具集中做事件列表断言，避免测试主体被样板代码淹没。 */
    static final class MvcResultHelper {
        static void events(CrossPeriodTest t, String account, String period, int expected) throws Exception {
            var result = t.mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/events").param("accountId", account).param("period", period))
                    .andReturn();
            JsonNode list = t.json(result);
            assertEquals(expected, list.size(), period + " 事件数应为 " + expected);
        }

        static void denied(CrossPeriodTest t) throws Exception {
            var result = t.writeEvent(409, "x-feb-late", "acct-M", "api_calls", 2,
                    CrossPeriodTest.utc(2026, 2, 20));
            t.assertErrorCode(result, "PERIOD_CLOSED");
        }
    }
}
