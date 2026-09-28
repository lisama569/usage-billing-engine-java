package com.github.lisama569.usagebillingenginejava.tests;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.domain.OperatorRole;
import com.github.lisama569.usagebillingenginejava.service.PeriodService;
import com.github.lisama569.usagebillingenginejava.service.EventService;
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
 * 账期门禁与角色/状态区分：
 * - CLOSED 账期普通写入 => PERIOD_CLOSED(423)；
 * - 普通用户尝试关闭/重开 => FORBIDDEN(403)；重复关闭、对 OPEN 账期重开 => PERIOD_STATE_CONFLICT(409)；
 * - REOPENED 账期只接受管理员纠错事件，普通写入仍是 PERIOD_CLOSED；
 * - 并发"一边关闭账期、一边写事件"：要么事件在关闭前受理、要么被拒，不出现丢失已受理或重复累计。
 */
@SpringBootTest
class ClosedPeriodAccessTest {

    private static final Logger log = LoggerFactory.getLogger(ClosedPeriodAccessTest.class);

    @Autowired
    private EventService eventService;
    @Autowired
    private PeriodService periodService;

    @Test
    void closedPeriodRejectsNormalWrite_andReasonsAreDistinguishable() {
        String accountId = "acc-close-1";
        String period = "2026-04";
        eventService.ingest("c1", accountId, "m", 1, Instant.parse("2026-04-05T00:00:00Z"),
                OperatorRole.USER);
        periodService.close(accountId, period);
        log.info("[输入] 账期 {}/{} 已关闭（关闭前1条事件），尝试普通写入 c2", accountId, period);

        BillingException closed = assertThrows(BillingException.class, () ->
                eventService.ingest("c2", accountId, "m", 1,
                        Instant.parse("2026-04-06T00:00:00Z"), OperatorRole.USER));
        assertEquals(ErrorCode.PERIOD_CLOSED, closed.getErrorCode());
        log.info("[判定依据] 关闭后写入 errorCode={} httpStatus=423", closed.getErrorCode());

        // 重复关闭 => 状态冲突（409，与 423 区分）
        BillingException dupClose = assertThrows(BillingException.class,
                () -> periodService.close(accountId, period));
        assertEquals(ErrorCode.PERIOD_STATE_CONFLICT, dupClose.getErrorCode());
        log.info("[判定依据] 重复关闭 errorCode={} httpStatus=409", dupClose.getErrorCode());

        // 对 OPEN 账期重开 => 状态冲突；重开必须提供原因 => 校验失败（400）
        BillingException reopenOpen = assertThrows(BillingException.class,
                () -> periodService.reopen("acc-never", period, "audit"));
        assertEquals(ErrorCode.PERIOD_STATE_CONFLICT, reopenOpen.getErrorCode());
        BillingException noReason = assertThrows(BillingException.class,
                () -> periodService.reopen(accountId, period, "  "));
        assertEquals(ErrorCode.VALIDATION_FAILED, noReason.getErrorCode());
        log.info("[判定依据] OPEN重开=PERIOD_STATE_CONFLICT, 空原因=VALIDATION_FAILED");

        // 受控重开后：普通用户写入仍被拒（纠错专属）
        periodService.reopen(accountId, period, "补录 4 月漏报");
        BillingException userInReopened = assertThrows(BillingException.class, () ->
                eventService.ingest("c3", accountId, "m", 1,
                        Instant.parse("2026-04-07T00:00:00Z"), OperatorRole.USER));
        assertEquals(ErrorCode.PERIOD_CLOSED, userInReopened.getErrorCode());
        log.info("[判定依据] REOPENED 账期普通用户写入 errorCode={}（仅管理员纠错可写）",
                userInReopened.getErrorCode());

        // 已受理事件一条未丢
        assertEquals(1, eventService.list(accountId, period).size());
    }

    @Test
    void concurrentCloseVsIngest_isConsistent() throws Exception {
        String accountId = "acc-close-2";
        String period = "2026-05";
        // 关闭前先在途写入 7 条，保证"关闭前已受理"分支被实际覆盖
        int preAccepted = 7;
        for (int i = 0; i < preAccepted; i++) {
            eventService.ingest("pre-" + i, accountId, "m", 1,
                    Instant.parse("2026-05-0" + (i + 1) + "T00:00:00Z"), OperatorRole.USER);
        }
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + 1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejectedClosed = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();

        pool.submit(() -> {
            try {
                start.await();
                // 让写入线程先抢，覆盖"关闭前抢入受理"分支；具体谁先抢到不影响不变量
                Thread.sleep(20);
                periodService.close(accountId, period);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        for (int i = 0; i < writers; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    eventService.ingest("race-" + idx, accountId, "m", 1,
                            Instant.parse("2026-05-1" + Math.min(idx, 9) + "T00:00:00Z"),
                            OperatorRole.USER);
                    accepted.incrementAndGet();
                } catch (BillingException e) {
                    if (e.getErrorCode() == ErrorCode.PERIOD_CLOSED) {
                        rejectedClosed.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherErrors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();

        int stored = eventService.list(accountId, period).size();
        log.info("[判定依据] 并发关闭vs写入: 关闭前在途={} 并发受理={} 因关闭拒绝={} 其他错误={} 实际存储={}",
                preAccepted, accepted.get(), rejectedClosed.get(), otherErrors.get(), stored);
        assertEquals(0, otherErrors.get());
        assertEquals(preAccepted + accepted.get(), stored,
                "关闭前在途与抢入事件都必须存在：受理总数=存储数，不丢量、不重复");
        assertEquals(writers, accepted.get() + rejectedClosed.get(), "每个写入要么受理要么因关闭被拒");
    }
}
