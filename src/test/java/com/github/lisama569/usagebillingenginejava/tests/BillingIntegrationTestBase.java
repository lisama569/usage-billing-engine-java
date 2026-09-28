package com.github.lisama569.usagebillingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试基类：每个测试类独立内存库（默认 context 缓存 + 每类新库，见 @TestPropertySource），
 * 提供事件/价格/账期/纠错的快捷调用与可区分错误断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BillingIntegrationTestBase {

    protected static final Logger log = LoggerFactory.getLogger(BillingIntegrationTestBase.class);
    protected static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    protected MockMvc mockMvc;

    /** UTC 日期 00:00 的 epoch ms */
    protected static long utc(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    protected static long utc(int year, int month, int day, int hour) {
        return Instant.parse(String.format("%04d-%02d-%02dT%02d:00:00Z", year, month, day, hour)).toEpochMilli();
    }

    // ---------- 普通事件 ----------

    protected MvcResult writeEvent(int expectStatus, String eventId, String account,
                                   String metric, long qty, long timeMs) throws Exception {
        String body = String.format(
                "{\"eventId\":\"%s\",\"accountId\":\"%s\",\"metric\":\"%s\",\"quantity\":%d,\"eventTimeEpochMs\":%d}",
                eventId, account, metric, qty, timeMs);
        log.info(">> 写事件期望{} body={}", expectStatus, body);
        return mockMvc.perform(post("/api/events").contentType("application/json").content(body))
                .andExpect(status().is(expectStatus))
                .andReturn();
    }

    // ---------- 管理操作 ----------

    /** 原始写入，不预设状态（供并发线程自行按状态分类）。 */
    protected MvcResult writeEventRaw(String eventId, String account, String metric,
                                      long qty, long timeMs) throws Exception {
        String body = String.format(
                "{\"eventId\":\"%s\",\"accountId\":\"%s\",\"metric\":\"%s\",\"quantity\":%d,\"eventTimeEpochMs\":%d}",
                eventId, account, metric, qty, timeMs);
        return mockMvc.perform(post("/api/events").contentType("application/json").content(body))
                .andReturn();
    }

    protected MvcResult publishPlan(int expectStatus, String account, String metric,
                                    long unitPrice, long validFrom, String role) throws Exception {
        String body = String.format(
                "{\"accountId\":\"%s\",\"metric\":\"%s\",\"unitPrice\":%d,\"validFromEpochMs\":%d}",
                account, metric, unitPrice, validFrom);
        log.info(">> 发布价格期望{} body={}, role={}", expectStatus, body, role);
        return mockMvc.perform(post("/api/admin/plans")
                        .header("X-Operator-Role", role).header("X-Operator-Id", "tester")
                        .contentType("application/json").content(body))
                .andExpect(status().is(expectStatus))
                .andReturn();
    }

    protected MvcResult closePeriod(int expectStatus, String account, String period, String role)
            throws Exception {
        return adminPost("/api/admin/periods/close", expectStatus, account, period, role);
    }

    protected MvcResult reopenPeriod(int expectStatus, String account, String period, String role)
            throws Exception {
        return adminPost("/api/admin/periods/reopen", expectStatus, account, period, role);
    }

    protected MvcResult recompute(int expectStatus, String account, String period, String role)
            throws Exception {
        return adminPost("/api/admin/periods/recompute", expectStatus, account, period, role);
    }

    private MvcResult adminPost(String url, int expectStatus, String account, String period, String role)
            throws Exception {
        String body = String.format("{\"accountId\":\"%s\",\"period\":\"%s\"}", account, period);
        log.info(">> {} 期望{} body={}, role={}", url, expectStatus, body, role);
        var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url)
                .header("X-Operator-Id", "tester")
                .contentType("application/json").content(body);
        if (role != null) {
            builder = builder.header("X-Operator-Role", role);
        }
        return mockMvc.perform(builder)
                .andExpect(status().is(expectStatus))
                .andReturn();
    }

    protected MvcResult correction(int expectStatus, String role, String json) throws Exception {
        log.info(">> 纠错期望{} role={} body={}", expectStatus, role, json);
        return mockMvc.perform(post("/api/admin/corrections")
                        .header("X-Operator-Role", role).header("X-Operator-Id", "auditor-a")
                        .contentType("application/json").content(json))
                .andExpect(status().is(expectStatus))
                .andReturn();
    }

    protected MvcResult getBill(String account, String period) throws Exception {
        return mockMvc.perform(get("/api/bills")
                        .param("accountId", account).param("period", period))
                .andExpect(status().isOk())
                .andReturn();
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    protected void assertErrorCode(MvcResult result, String code) throws Exception {
        JsonNode node = json(result);
        log.info("<< 错误响应 code={}, message={}", node.path("code").asText(), node.path("message").asText());
        org.junit.jupiter.api.Assertions.assertEquals(code, node.path("code").asText(),
                "错误码应为 " + code + "，实际=" + node.path("code").asText());
    }
}
