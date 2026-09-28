package com.github.lisama569.usagebillingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 账期生命周期与权限边界：
 * - CLOSED 账期普通写入 -> PERIOD_CLOSED；REOPENED 普通写入仍 -> PERIOD_CLOSED；
 * - 非 ADMIN 调管理接口 -> 403 FORBIDDEN；
 * - OPEN 上重开 -> 409 PERIOD_STATUS_CONFLICT；不存在账期重开 -> 404 NOT_FOUND；
 * - 参数缺失 -> 422 VALIDATION_FAILED；
 * - 重复关账幂等，不产生第二张账单/重复扣费。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_period_lifecycle;DB_CLOSE_DELAY=-1"
})
class PeriodLifecycleAuthTest extends BillingIntegrationTestBase {

    @Test
    void closedPeriodRejectsWritesAndErrorsAreDistinguishable() throws Exception {
        publishPlan(200, "acct-L", "api_calls", 1, utc(2026, 7, 1), "ADMIN");
        writeEvent(200, "l-1", "acct-L", "api_calls", 10, utc(2026, 7, 5));

        // 关账
        JsonNode closed = json(closePeriod(200, "acct-L", "2026-07", "ADMIN"));
        assertEquals("CLOSED", closed.path("status").asText());
        JsonNode bill1 = json(getBill("acct-L", "2026-07"));
        assertEquals(1, bill1.path("version").asInt());
        log.info("判定依据: 首次关账生成账单 version=1, amount={}", bill1.path("totalAmount").asLong());

        // 关账后普通写入 -> PERIOD_CLOSED
        MvcResult writeDenied = writeEvent(409, "l-2", "acct-L", "api_calls", 1, utc(2026, 7, 6));
        assertErrorCode(writeDenied, "PERIOD_CLOSED");

        // 同 eventId 载荷冲突仍是 EVENT_ID_CONFLICT（与 PERIOD_CLOSED 可区分：先发再关账）
        MvcResult idConflict = writeEvent(409, "l-1", "acct-L", "api_calls", 999, utc(2026, 7, 5));
        assertErrorCode(idConflict, "EVENT_ID_CONFLICT");
        log.info("判定依据: 关账后写入=PERIOD_CLOSED，同 id 不同载荷=EVENT_ID_CONFLICT，原因可区分");

        // 重复关账幂等
        JsonNode closedAgain = json(closePeriod(200, "acct-L", "2026-07", "ADMIN"));
        assertEquals("CLOSED", closedAgain.path("status").asText());
        JsonNode billAfter = json(getBill("acct-L", "2026-07"));
        assertEquals(1, billAfter.path("version").asInt(), "重复关账不得产生新版本");
        assertEquals(10, billAfter.path("totalQuantity").asLong(), "重复关账金额不得变化");
        log.info("判定依据: 重复关账后账单仍 version=1, amount={}，未重复扣费", billAfter.path("totalAmount").asLong());

        // 重开
        JsonNode reopened = json(reopenPeriod(200, "acct-L", "2026-07", "ADMIN"));
        assertEquals("REOPENED", reopened.path("status").asText());
        assertEquals(1, reopened.path("reopenCount").asInt());

        // REOPENED 下普通写入仍然被拒
        MvcResult normalWriteOnReopened =
                writeEvent(409, "l-3", "acct-L", "api_calls", 1, utc(2026, 7, 7));
        assertErrorCode(normalWriteOnReopened, "PERIOD_CLOSED");
        log.info("判定依据: REOPENED 仅纠错可写，普通接口仍 PERIOD_CLOSED");

        // OPEN 账期重开 -> 状态冲突；不存在的账期重开 -> NOT_FOUND（两者区分）
        MvcResult writeAug = writeEvent(200, "l-aug", "acct-L", "api_calls", 1, utc(2026, 8, 1));
        MvcResult reopenOpen2 = reopenPeriod(409, "acct-L", "2026-08", "ADMIN");
        assertErrorCode(reopenOpen2, "PERIOD_STATUS_CONFLICT");
        MvcResult reopenMissing = reopenPeriod(404, "acct-L", "2026-09", "ADMIN");
        assertErrorCode(reopenMissing, "NOT_FOUND");
        log.info("判定依据: OPEN 账期重开=PERIOD_STATUS_CONFLICT，不存在账期重开=NOT_FOUND");
    }

    @Test
    void nonAdminForbiddenAndValidationFailedAreDistinct() throws Exception {
        // 无角色头 -> 403
        MvcResult noRole = closePeriod(403, "acct-X", "2026-07", null);
        assertErrorCode(noRole, "FORBIDDEN");
        // 错误角色 -> 403
        MvcResult viewer = closePeriod(403, "acct-X", "2026-07", "VIEWER");
        assertErrorCode(viewer, "FORBIDDEN");
        log.info("判定依据: 缺角色/非 ADMIN 均为 403 FORBIDDEN");

        // 422 参数校验：quantity 为负
        MvcResult invalid = writeEvent(422, "bad-1", "acct-X", "api_calls", -5, utc(2026, 7, 1));
        assertErrorCode(invalid, "VALIDATION_FAILED");
        log.info("判定依据: quantity<=0 -> 422 VALIDATION_FAILED");
    }
}
