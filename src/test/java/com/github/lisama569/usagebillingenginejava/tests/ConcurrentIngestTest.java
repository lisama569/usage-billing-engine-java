package com.github.lisama569.usagebillingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发场景：
 * 1) 同一 eventId 被 N 个线程同时提交 -> 恰好 1 次首次计入，其余幂等重放，无 5xx/锁超时；
 * 2) 一批不同 eventId 并发写入同一账期 -> 全部首次计入，总量精确求和，不丢量。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_concurrent;DB_CLOSE_DELAY=-1"
})
class ConcurrentIngestTest extends BillingIntegrationTestBase {

    @Test
    void concurrentDuplicateSubmissionsCountedOnce() throws Exception {
        int threads = 16;
        long t = utc(2026, 4, 15);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger firstAccepted = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    MvcResult r = writeEvent(200, "evt-dup", "acct-C", "api_calls", 5, t);
                    JsonNode body = json(r);
                    if (body.path("idempotentReplay").asBoolean()) {
                        replays.incrementAndGet();
                    } else {
                        firstAccepted.incrementAndGet();
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写入必须在锁等待窗口内全部完成");
        pool.shutdown();

        log.info("判定依据: 同 eventId 并发 {} 线程 -> 首次计入={}, 幂等重放={}, 异常={}",
                threads, firstAccepted.get(), replays.get(), errors.size());
        assertEquals(0, errors.size(), "并发重复提交不应产生异常: " + errors);
        assertEquals(1, firstAccepted.get(), "恰好一个线程首次计入");
        assertEquals(threads - 1, replays.get(), "其余线程全部幂等重放");

        MvcResult events = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/events").param("accountId", "acct-C").param("period", "2026-04"))
                .andReturn();
        JsonNode list = json(events);
        assertEquals(1, list.size(), "并发重复提交后只落一条事件");
        assertEquals(5, list.get(0).path("quantity").asLong());
    }

    @Test
    void concurrentDistinctEventsAllRetained() throws Exception {
        int threads = 20;
        long t = utc(2026, 4, 16);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    writeEvent(200, "evt-distinct-" + idx, "acct-D", "api_calls", idx + 1, t);
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long expected = 0;
        for (int i = 0; i < threads; i++) {
            expected += i + 1L;
        }
        log.info("判定依据: {} 个不同 eventId 并发写入，期望总量={}, 异常={}", threads, expected, errors.size());
        assertEquals(0, errors.size(), "并发写入不应有异常: " + errors);

        MvcResult events = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/events").param("accountId", "acct-D").param("period", "2026-04"))
                .andReturn();
        JsonNode list = json(events);
        long actual = 0;
        for (JsonNode e : list) {
            actual += e.path("quantity").asLong();
        }
        assertEquals(threads, list.size(), "不同事件不得丢失");
        assertEquals(expected, actual, "并发总量必须精确守恒，期望=" + expected + " 实际=" + actual);
        log.info("判定依据: 事件数={}, 实际总量={}，与串行期望一致", list.size(), actual);
    }
}
