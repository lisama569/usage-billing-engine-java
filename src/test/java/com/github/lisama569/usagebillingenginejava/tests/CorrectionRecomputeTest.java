package com.github.lisama569.usagebillingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 受控重开 + 人工纠错 + 重算 + 重新关账：
 * 关账 amount=100 -> 重开 -> 补录 +20、冲正 -30 -> 新账单 amount=90；
 * 冲正事件保留 reversed 标记且数量计入 reversedQuantity（总量守恒、可审计）；
 * correctionId 重试幂等；重复冲正 CORRECTION_CONFLICT；
 * OPEN 账期纠错 PERIOD_STATUS_CONFLICT；非 ADMIN 纠错 FORBIDDEN；
 * 重新关账后 FINAL 与最新草稿一致，不产生额外扣费。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_correction;DB_CLOSE_DELAY=-1"
})
class CorrectionRecomputeTest extends BillingIntegrationTestBase {

    private String correctionJson(String correctionId, String type, String eventId,
                                  String metric, Long qty, Long time, String reason) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"correctionId\":\"").append(correctionId).append("\",");
        sb.append("\"accountId\":\"acct-Z\",\"period\":\"2026-02\",");
        sb.append("\"type\":\"").append(type).append("\",");
        sb.append("\"eventId\":\"").append(eventId).append("\",");
        if (metric != null) sb.append("\"metric\":\"").append(metric).append("\",");
        if (qty != null) sb.append("\"quantity\":").append(qty).append(",");
        if (time != null) sb.append("\"eventTimeEpochMs\":").append(time).append(",");
        sb.append("\"reason\":\"").append(reason).append("\"}");
        return sb.toString();
    }

    @Test
    void fullCorrectionFlowIsConservedAndIdempotent() throws Exception {
        publishPlan(200, "acct-Z", "api_calls", 10, utc(2026, 2, 1), "ADMIN");
        // 事件：z-1 qty=6（将冲正），z-2 qty=4 => 有效 10，amount=100
        writeEvent(200, "z-1", "acct-Z", "api_calls", 6, utc(2026, 2, 10));
        writeEvent(200, "z-2", "acct-Z", "api_calls", 4, utc(2026, 2, 11));

        JsonNode initial = json(getBill("acct-Z", "2026-02"));
        assertEquals(100, initial.path("totalAmount").asLong());

        JsonNode closed = json(closePeriod(200, "acct-Z", "2026-02", "ADMIN"));
        assertEquals("CLOSED", closed.path("status").asText());
        JsonNode finalV1 = json(getBill("acct-Z", "2026-02"));
        assertEquals(1, finalV1.path("version").asInt());
        log.info("判定依据: 首次关账 v1 amount=100, totalQty=10, reversedQty=0");

        // CLOSED 上直接纠错 -> PERIOD_STATUS_CONFLICT
        String backfill = correctionJson("c-1", "BACKFILL", "z-3", "api_calls",
                2L, utc(2026, 2, 12), "补录遗漏事件");
        MvcResult onClosed = correction(409, "ADMIN", backfill);
        assertErrorCode(onClosed, "PERIOD_STATUS_CONFLICT");

        // 非 ADMIN 不能重开
        assertErrorCode(reopenPeriod(403, "acct-Z", "2026-02", "VIEWER"), "FORBIDDEN");
        // 非 ADMIN 重开后也不能纠错（先 ADMIN 重开）
        JsonNode reopened = json(reopenPeriod(200, "acct-Z", "2026-02", "ADMIN"));
        assertEquals("REOPENED", reopened.path("status").asText());
        MvcResult viewerCorrection = correction(403, "VIEWER", backfill);
        assertErrorCode(viewerCorrection, "FORBIDDEN");
        log.info("判定依据: 非 ADMIN 重开/纠错均 403 FORBIDDEN");

        // OPEN 账期上纠错 -> PERIOD_STATUS_CONFLICT
        String otherBackfill = correctionJson("c-x", "BACKFILL", "o-1", "api_calls",
                1L, utc(2026, 3, 1), "x").replace("\"2026-02\"", "\"2026-03\"");
        writeEvent(200, "seed-mar", "acct-Z", "api_calls", 1, utc(2026, 3, 1));
        MvcResult onOpen = correction(409, "ADMIN", otherBackfill);
        assertErrorCode(onOpen, "PERIOD_STATUS_CONFLICT");
        log.info("判定依据: OPEN 账期纠错 PERIOD_STATUS_CONFLICT（应走普通写入）");

        // 补录 z-3 qty=2（+20）
        JsonNode afterBackfill = json(correction(200, "ADMIN", backfill));
        assertEquals(2, afterBackfill.path("version").asInt(), "纠错产生新版本");
        assertEquals(120, afterBackfill.path("totalAmount").asLong(), "100 + 2*10 = 120");
        assertEquals(12, afterBackfill.path("totalQuantity").asLong());
        assertEquals(0, afterBackfill.path("reversedQuantity").asLong());
        log.info("判定依据: BACKFILL 后 v2 amount=120, totalQty=12");

        // 补录重试（同 correctionId）-> 幂等，不产生新版本
        JsonNode backfillReplay = json(correction(200, "ADMIN", backfill));
        assertEquals(2, backfillReplay.path("version").asInt(), "重复纠错不得再产生版本");
        log.info("判定依据: 相同 correctionId 重试幂等，版本仍为 v2");

        // 冲正 z-1 qty=6（-60）-> amount=60，有效 6，冲正 6
        String reversal = correctionJson("c-2", "REVERSAL", "z-1", null,
                null, null, "该事件重复上报");
        JsonNode afterReversal = json(correction(200, "ADMIN", reversal));
        assertEquals(3, afterReversal.path("version").asInt());
        assertEquals(60, afterReversal.path("totalAmount").asLong(), "(10+2-6)*10 = 60");
        assertEquals(6, afterReversal.path("totalQuantity").asLong());
        assertEquals(6, afterReversal.path("reversedQuantity").asLong(), "冲正量被单列，总量守恒");
        JsonNode reversedLine = null;
        for (JsonNode line : afterReversal.path("lines")) {
            if ("z-1".equals(line.path("eventId").asText())) {
                reversedLine = line;
            }
        }
        assertEquals(Boolean.TRUE, reversedLine.path("reversed").asBoolean());
        assertEquals(0, reversedLine.path("lineAmount").asLong(), "冲正行金额为 0");
        log.info("判定依据: REVERSAL 后 v3 amount=60, 有效量=6, 冲正量=6（行保留，金额0，守恒）");

        // 重复冲正 -> CORRECTION_CONFLICT
        String reversalAgain = correctionJson("c-3", "REVERSAL", "z-1", null,
                null, null, "再次冲正");
        assertErrorCode(correction(409, "ADMIN", reversalAgain), "CORRECTION_CONFLICT");
        // 冲正不存在事件 -> NOT_FOUND
        String reversalMissing = correctionJson("c-4", "REVERSAL", "nope", null,
                null, null, "x");
        assertErrorCode(correction(404, "ADMIN", reversalMissing), "NOT_FOUND");
        // 补录校验失败：eventTime 归属别的账期 -> VALIDATION_FAILED
        String wrongPeriod = correctionJson("c-5", "BACKFILL", "z-9", "api_calls",
                1L, utc(2026, 3, 20), "时间落在3月");
        assertErrorCode(correction(422, "ADMIN", wrongPeriod), "VALIDATION_FAILED");
        log.info("判定依据: 重复冲正=CORRECTION_CONFLICT, 目标缺失=NOT_FOUND, 跨账期补录=VALIDATION_FAILED");

        // 重算结果与 v3 一致（确定性/可复现）
        JsonNode recomputed = json(recompute(200, "acct-Z", "2026-02", "ADMIN"));
        assertEquals(60, recomputed.path("totalAmount").asLong());
        log.info("判定依据: REOPENED 重算 v4 amount 仍=60，结果只由事件集与历史价格决定");

        // 重新关账：FINAL 与草稿一致，不产生额外扣费
        JsonNode finalClosed = json(closePeriod(200, "acct-Z", "2026-02", "ADMIN"));
        assertEquals("CLOSED", finalClosed.path("status").asText());
        JsonNode finalBill = json(getBill("acct-Z", "2026-02"));
        assertEquals("FINAL", finalBill.path("status").asText());
        assertEquals(60, finalBill.path("totalAmount").asLong(), "纠错后最终金额=60");
        assertEquals(6, finalBill.path("reversedQuantity").asLong());
        log.info("判定依据: 重新关账 FINAL amount=60，与纠错结果一致，无重复扣费");
    }
}
