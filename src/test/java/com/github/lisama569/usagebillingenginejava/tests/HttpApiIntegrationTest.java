package com.github.lisama569.usagebillingenginejava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP 层验证：本地可直接调用的 REST 接口、角色头与可区分状态码。
 */
@SpringBootTest
class HttpApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpApiIntegrationTest.class);

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    void endToEnd_httpStatusCodesAreDistinguishable() throws Exception {
        MockMvc mvc = mvc();
        // 管理员先发布价格
        mvc.perform(post("/api/prices").header("X-Operator-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"acc-http","metric":"m","unitPriceMinor":1,
                                 "effectiveFrom":"2026-07-01T00:00:00Z"}"""))
                .andExpect(status().isOk());
        log.info("[判定依据] ADMIN 发布价格 200");

        // 普通用户发布价格 => 403
        mvc.perform(post("/api/prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"acc-http","metric":"m","unitPriceMinor":2,
                                 "effectiveFrom":"2026-07-01T00:00:00Z"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        log.info("[判定依据] USER 发布价格 => 403 FORBIDDEN");

        // 写入事件 200；重复提交仍 200 duplicate=true；冲突 409
        String eventJson = """
                {"eventId":"h1","accountId":"acc-http","metric":"m","quantity":5,
                 "eventTime":"2026-07-05T00:00:00Z"}""";
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(eventJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(false));
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(eventJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"h1","accountId":"acc-http","metric":"m","quantity":6,
                                 "eventTime":"2026-07-05T00:00:00Z"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EVENT_ID_CONFLICT"));
        log.info("[判定依据] 重复=200 duplicate，冲突=409 EVENT_ID_CONFLICT");

        // 普通用户关闭账期 => 403；管理员关闭 => 200；重复关闭 => 409
        mvc.perform(post("/api/periods/close?accountId=acc-http&period=2026-07"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/periods/close?accountId=acc-http&period=2026-07")
                        .header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/periods/close?accountId=acc-http&period=2026-07")
                        .header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PERIOD_STATE_CONFLICT"));
        log.info("[判定依据] 关闭: 403/200，重复关闭=409");

        // 关闭后普通写入 => 423
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"h2","accountId":"acc-http","metric":"m","quantity":1,
                                 "eventTime":"2026-07-06T00:00:00Z"}"""))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.errorCode").value("PERIOD_CLOSED"));
        log.info("[判定依据] 关闭后写入 => 423 PERIOD_CLOSED");

        // CLOSED 账期做 USAGE_DELTA（未重开）=> 422
        mvc.perform(post("/api/bills/corrections?accountId=acc-http&period=2026-07")
                        .header("X-Operator-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"correctionId":"hc1","type":"USAGE_DELTA","metric":"m",
                                 "signedQuantity":1,"signedAmountMinor":0,"reason":"x"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CORRECTION_NOT_ALLOWED"));
        log.info("[判定依据] CLOSED 账期 USAGE_DELTA => 422 CORRECTION_NOT_ALLOWED");

        // 金额调整纠错 => 200，账单 -100（给 -100 分）
        mvc.perform(post("/api/bills/corrections?accountId=acc-http&period=2026-07")
                        .header("X-Operator-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"correctionId":"hc2","type":"AMOUNT_ADJUSTMENT","metric":"m",
                                 "signedQuantity":0,"signedAmountMinor":-3,"reason":"refund"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmountMinor").value(5 - 3));
        log.info("[判定依据] AMOUNT_ADJUSTMENT -3 后金额=2（原 5×1=5）");
    }
}
