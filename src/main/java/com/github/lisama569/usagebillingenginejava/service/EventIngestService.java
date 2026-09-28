package com.github.lisama569.usagebillingenginejava.service;

import com.github.lisama569.usagebillingenginejava.api.dto.UsageEventRequest;
import com.github.lisama569.usagebillingenginejava.api.dto.UsageEventView;
import com.github.lisama569.usagebillingenginejava.api.dto.WriteResult;
import com.github.lisama569.usagebillingenginejava.domain.BillingPeriodStatus;
import com.github.lisama569.usagebillingenginejava.domain.UsageEvent;
import com.github.lisama569.usagebillingenginejava.error.BillingException;
import com.github.lisama569.usagebillingenginejava.error.ErrorCode;
import com.github.lisama569.usagebillingenginejava.repo.UsageEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);

    private final UsageEventRepository eventRepo;
    private final TxActions txActions;

    public EventIngestService(UsageEventRepository eventRepo, TxActions txActions) {
        this.eventRepo = eventRepo;
        this.txActions = txActions;
    }

    /**
     * 写入用量事件（单事务 + 账期命名锁，与关账严格互斥线性化）：
     * 1) eventId 已存在且载荷一致 -> 幂等重放；载荷不同 -> EVENT_ID_CONFLICT；
     * 2) 锁内账期为 CLOSED/REOPENED -> PERIOD_CLOSED（事件不插入）；
     * 3) 否则插入 ACTIVE 事件并随锁事务提交：提交成功即必然被后续关账计入，
     *    不会出现“返回接受但 FINAL 漏计”的在途窗口。
     */
    public WriteResult record(UsageEventRequest req) {
        log.info("收到事件写入 eventId={}, account={}, metric={}, qty={}, eventTime={}",
                req.eventId(), req.accountId(), req.metric(), req.quantity(), req.eventTimeEpochMs());

        UsageEvent existing = txActions.findEvent(req.eventId());
        if (existing != null) {
            return replayOrConflict(existing, req);
        }

        String period = PeriodKeys.ofEpochMs(req.eventTimeEpochMs());
        txActions.ensureLockRow(req.accountId(), period);
        txActions.ensurePeriodRow(req.accountId(), period);

        try {
            txActions.insertEventUnderLock(newEvent(req, period));
        } catch (TxActions.PeriodNotOpenException notOpen) {
            assertWritableForNormal(req.accountId(), period,
                    BillingPeriodStatus.valueOf(notOpen.getStatus()));
        } catch (DataIntegrityViolationException dup) {
            // eventId 唯一约束撞键（账期行已独立建好）：回查 winner 按幂等/冲突重判
            UsageEvent winner = txActions.findEvent(req.eventId());
            if (winner == null) {
                throw new BillingException(ErrorCode.EVENT_ID_CONFLICT,
                        "eventId=" + req.eventId() + " 并发冲突且回查失败，请重试");
            }
            log.info("写入撞 eventId 唯一约束，回查到 winner，按幂等/冲突重判 eventId={}", req.eventId());
            return replayOrConflict(winner, req);
        }
        log.info("事件首次计入 eventId={}, period={}, 判定=ACCEPTED", req.eventId(), period);
        return new WriteResult(req.eventId(), period, true, false);
    }

    @Transactional(readOnly = true)
    public List<UsageEventView> query(String accountId, String period) {
        List<UsageEvent> events = new ArrayList<>();
        events.addAll(eventRepo.findByAccountIdAndPeriodAndStateOrderByEventTimeAsc(
                accountId, period, "ACTIVE"));
        events.addAll(eventRepo.findByAccountIdAndPeriodAndStateOrderByEventTimeAsc(
                accountId, period, "REVERSED"));
        events.sort(Comparator.comparingLong(UsageEvent::getEventTime));
        log.info("查询事件 account={}, period={}, ACTIVE+REVERSED 命中 {} 条", accountId, period, events.size());
        return events.stream().map(EventIngestService::toView).toList();
    }

    private WriteResult replayOrConflict(UsageEvent existing, UsageEventRequest req) {
        boolean samePayload = existing.getAccountId().equals(req.accountId())
                && existing.getMetric().equals(req.metric())
                && existing.getQuantity().equals(req.quantity())
                && existing.getEventTime().equals(req.eventTimeEpochMs());
        if (samePayload) {
            log.info("命中幂等 eventId={}, period={}, state={}, 判定=IDEMPOTENT_REPLAY（不重复累计）",
                    req.eventId(), existing.getPeriod(), existing.getState());
            return new WriteResult(req.eventId(), existing.getPeriod(), true, true);
        }
        log.warn("事件标识冲突 eventId={}, 已存载荷=[{},{},qty={},t={}], 新载荷=[{},{},qty={},t={}], 判定=EVENT_ID_CONFLICT",
                req.eventId(),
                existing.getAccountId(), existing.getMetric(), existing.getQuantity(), existing.getEventTime(),
                req.accountId(), req.metric(), req.quantity(), req.eventTimeEpochMs());
        throw new BillingException(ErrorCode.EVENT_ID_CONFLICT,
                "eventId=" + req.eventId() + " 已被不同载荷的事件占用，拒绝写入");
    }

    private void assertWritableForNormal(String accountId, String period, BillingPeriodStatus status) {
        if (status == BillingPeriodStatus.CLOSED) {
            log.warn("账期已关闭 account={}, period={}, status=CLOSED, 判定=PERIOD_CLOSED", accountId, period);
            throw new BillingException(ErrorCode.PERIOD_CLOSED,
                    "账期 " + period + " 已关闭（CLOSED），普通事件写入被拒绝");
        }
        if (status == BillingPeriodStatus.REOPENED) {
            log.warn("账期受控重开 account={}, period={}, status=REOPENED, 普通写入判定=PERIOD_CLOSED",
                    accountId, period);
            throw new BillingException(ErrorCode.PERIOD_CLOSED,
                    "账期 " + period
                            + " 处于受控重开（REOPENED）状态，仅允许 ADMIN 通过纠错接口写入");
        }
    }

    private UsageEvent newEvent(UsageEventRequest req, String period) {
        UsageEvent event = new UsageEvent();
        event.setEventId(req.eventId());
        event.setAccountId(req.accountId());
        event.setMetric(req.metric());
        event.setQuantity(req.quantity());
        event.setEventTime(req.eventTimeEpochMs());
        event.setReceivedAt(System.currentTimeMillis());
        event.setPeriod(period);
        event.setState("ACTIVE");
        return event;
    }

    static UsageEventView toView(UsageEvent e) {
        return new UsageEventView(e.getEventId(), e.getAccountId(), e.getMetric(),
                e.getQuantity(), e.getEventTime(), e.getReceivedAt(), e.getPeriod(),
                e.isReversed(), e.getCorrectionId());
    }
}
