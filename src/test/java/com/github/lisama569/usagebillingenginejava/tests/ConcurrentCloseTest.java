package com.github.lisama569.usagebillingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关账与写入并发：一批写入线程与一个关账线程同时冲击同一账期。
 * 不变量：
 *  - 没有 5xx/死锁，全部在锁等待窗口内结束；
 *  - 被接受的事件总量 + 关账后被 PERIOD_CLOSED 拒绝的事件总量 = 提交总数（不丢、不重）；
 *  - 最终账期 CLOSED，账单 FINAL，账单总量 == 被接受事件的总量。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_concurrent_close;DB_CLOSE_DELAY=-1"
})
class ConcurrentCloseTest extends BillingIntegrationTestBase {

    @Test
    void closeRacesWithWritesWithoutLossOrDuplication() throws Exception {
        publishPlan(200, "acct-R", "api_calls", 1, utc(2026, 9, 1), "ADMIN");

        int writers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + 1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejectedClosed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < writers; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    MvcResult r = writeEventRaw("race-" + idx, "acct-R", "api_calls",
                            idx + 1, utc(2026, 9, 10));
                    int status = r.getResponse().getStatus();
                    if (status == 200) {
                        accepted.incrementAndGet();
                    } else if (status == 409
                            && "PERIOD_CLOSED".equals(json(r).path("code").asText())) {
                        rejectedClosed.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                        log.error("写入返回非预期状态 status={}, body={}",
                                status, r.getResponse().getContentAsString());
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("写入线程异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                closePeriod(200, "acct-R", "2026-09", "ADMIN");
            } catch (Exception e) {
                errors.incrementAndGet();
                log.error("关账线程异常", e);
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "关账与写入并发须在窗口内结束");
        pool.shutdown();

        log.info("判定依据: writers={}, 接受={}, 关账后拒绝={}, 5xx异常={}",
                writers, accepted.get(), rejectedClosed.get(), errors.get());
        assertEquals(0, errors.get(), "不应有 5xx/死锁");
        assertEquals(writers, accepted.get() + rejectedClosed.get(),
                "每个提交要么计入要么被 PERIOD_CLOSED 拒绝，不丢不重");

        JsonNode status = json(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/admin/periods").param("accountId", "acct-R").param("period", "2026-09")
                .header("X-Operator-Role", "ADMIN"))
                .andReturn());
        assertEquals("CLOSED", status.path("status").asText());

        JsonNode bill = json(getBill("acct-R", "2026-09"));
        assertEquals("FINAL", bill.path("status").asText());
        long sumFromLines = 0;
        for (JsonNode line : bill.path("lines")) {
            sumFromLines += line.path("quantity").asLong();
        }
        assertEquals(accepted.get(), bill.path("lines").size(), "FINAL 明细条数==被接受事件数");
        assertEquals(sumFromLines, bill.path("totalQuantity").asLong(), "FINAL 总量与明细一致");
        log.info("判定依据: CLOSED+FINAL，明细 {} 条，总量={}，与接受路径精确一致",
                bill.path("lines").size(), sumFromLines);
    }
}
