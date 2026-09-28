package com.github.lisama569.usagebillingenginejava.tests;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.domain.OperatorRole;
import com.github.lisama569.usagebillingenginejava.service.EventService;
import com.github.lisama569.usagebillingenginejava.service.PeriodService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发重复写入：
 * - 同一 eventId 被 N 线程同时提交，只应受理一次（不丢量、不重复累计）；
 * - 重复提交幂等成功；相同内容重试仍幂等；
 * - 同一 eventId 换内容 => EVENT_ID_CONFLICT，与重复成功明确区分。
 */
@SpringBootTest
class ConcurrentDuplicateIngestTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentDuplicateIngestTest.class);

    @Autowired
    private EventService eventService;
    @Autowired
    private PeriodService periodService;

    @Test
    void concurrentSameEvent_acceptedExactlyOnce() throws Exception {
        String accountId = "acc-conc-1";
        String eventId = "evt-conc-1";
        Instant eventTime = Instant.parse("2026-03-10T10:00:00Z");
        int threads = 24;
        log.info("[输入] account={} eventId={} qty=5 eventTime={} 并发线程={}", accountId, eventId, eventTime, threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    EventService.IngestResult r = eventService.ingest(eventId, accountId,
                            "storage_gb", 5, eventTime, OperatorRole.USER);
                    if (r.duplicate()) {
                        duplicate.incrementAndGet();
                    } else {
                        accepted.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("线程异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "并发写入应在 10s 内完成（不得长时间阻塞）");
        pool.shutdownNow();

        log.info("[判定依据] 首次受理数={} 幂等重复数={} 错误数={} (受理必须恰为1)", accepted.get(), duplicate.get(), errors.get());
        assertEquals(1, accepted.get(), "同一事件并发提交只能受理一次");
        assertEquals(threads - 1, duplicate.get(), "其余并发请求必须按幂等重复返回");
        assertEquals(0, errors.get());

        // 事件列表只应有一条，账期受理集合也只应有一个 eventId
        var events = eventService.list(accountId, "2026-03");
        log.info("[判定依据] 账期事件数={}（应为1），总量={}", events.size(),
                events.stream().mapToLong(e -> e.quantity()).sum());
        assertEquals(1, events.size());
        assertEquals(5, events.stream().mapToLong(e -> e.quantity()).sum());
        assertEquals(1, periodService.view(accountId, "2026-03").getAcceptedEventIds().size());

        // 相同请求再重试：仍幂等
        EventService.IngestResult retry = eventService.ingest(eventId, accountId,
                "storage_gb", 5, eventTime, OperatorRole.USER);
        assertTrue(retry.duplicate());
        assertEquals(1, eventService.list(accountId, "2026-03").size());
    }

    @Test
    void sameIdDifferentPayload_isConflictNotDuplicate() {
        String accountId = "acc-conc-2";
        String eventId = "evt-conc-2";
        Instant t1 = Instant.parse("2026-03-11T08:00:00Z");
        eventService.ingest(eventId, accountId, "storage_gb", 10, t1, OperatorRole.USER);
        log.info("[输入] 已写入 eventId={} qty=10；再用相同 eventId 提交 qty=11", eventId);

        BillingException ex = assertThrows(BillingException.class, () ->
                eventService.ingest(eventId, accountId, "storage_gb", 11, t1, OperatorRole.USER));
        log.info("[判定依据] 冲突请求被拒绝 errorCode={} message={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(ErrorCode.EVENT_ID_CONFLICT, ex.getErrorCode(), "标识冲突必须与重复成功区分");
    }
}
