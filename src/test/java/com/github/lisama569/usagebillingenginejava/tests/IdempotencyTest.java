package com.github.lisama569.usagebillingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等：同 eventId 同载荷重复提交只计一次；载荷不同返回 EVENT_ID_CONFLICT。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_idempotency;DB_CLOSE_DELAY=-1"
})
class IdempotencyTest extends BillingIntegrationTestBase {

    @Test
    void duplicateSamePayloadIsIdempotentAndConflictingPayloadRejected() throws Exception {
        long t = utc(2026, 3, 10);
        // 首次写入
        MvcResult first = writeEvent(200, "evt-1", "acct-A", "api_calls", 10, t);
        JsonNode firstBody = json(first);
        assertTrue(firstBody.path("accepted").asBoolean());
        assertFalse(firstBody.path("idempotentReplay").asBoolean());
        log.info("判定依据: 首次写入 accepted=true, idempotentReplay=false");

        // 同载荷重复 -> 幂等
        MvcResult replay = writeEvent(200, "evt-1", "acct-A", "api_calls", 10, t);
        JsonNode replayBody = json(replay);
        assertTrue(replayBody.path("accepted").asBoolean());
        assertTrue(replayBody.path("idempotentReplay").asBoolean(), "重复提交必须标记为幂等重放");
        log.info("判定依据: 同 eventId+同载荷 -> idempotentReplay=true，不重复累计");

        // 不同载荷 -> 409 EVENT_ID_CONFLICT
        MvcResult conflict = writeEvent(409, "evt-1", "acct-A", "api_calls", 99, t);
        assertErrorCode(conflict, "EVENT_ID_CONFLICT");
        log.info("判定依据: 同 eventId+不同 quantity -> 409 EVENT_ID_CONFLICT");

        // 账期内只有 1 条事件，没有重复累计
        MvcResult events = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/events").param("accountId", "acct-A").param("period", "2026-03"))
                .andReturn();
        JsonNode list = json(events);
        assertEquals(1, list.size(), "重复提交后事件仍只有一条");
        assertEquals(10, list.get(0).path("quantity").asLong(), "保留的是首次载荷 quantity=10");
        log.info("判定依据: 事件列表 size=1, quantity=10，幂等未造成重复累计");
    }
}
