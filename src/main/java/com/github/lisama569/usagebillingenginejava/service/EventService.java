package com.github.lisama569.usagebillingenginejava.service;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.domain.EventKind;
import com.github.lisama569.usagebillingenginejava.domain.OperatorRole;
import com.github.lisama569.usagebillingenginejava.domain.PeriodState;
import com.github.lisama569.usagebillingenginejava.domain.PeriodStatus;
import com.github.lisama569.usagebillingenginejava.domain.UsageEvent;
import com.github.lisama569.usagebillingenginejava.repository.EventRepository;
import com.github.lisama569.usagebillingenginejava.repository.PeriodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 用量事件写入与查询。
 *
 * 关键口径：
 * - 同一 eventId 且内容一致 => 幂等成功（duplicate=true），绝不重复累计；
 * - 同一 eventId 但业务字段不一致 => EVENT_ID_CONFLICT（409，与重复可区分）；
 * - 事件时间落在 CLOSED 账期 => PERIOD_CLOSED（423，与状态冲突 409 区分）；
 * - REOPENED 账期：仅 ADMIN 且 CORRECTION 事件可写；普通用户写入被拒；
 * - "查重-账期门禁-受理"在账期键的 bin 锁内原子完成，并发/重试下不丢不重不全局阻塞。
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(1);

    private final EventRepository events;
    private final PeriodRepository periods;
    private final Clock clock;

    public EventService(EventRepository events, PeriodRepository periods, Clock clock) {
        this.events = events;
        this.periods = periods;
        this.clock = clock;
    }

    /** 普通事件写入（开放 API，USER/ADMIN 均可调用）。 */
    public IngestResult ingest(String eventId, String accountId, String metric,
                               long quantity, Instant eventTime, OperatorRole role) {
        return doIngest(eventId, accountId, metric, quantity, eventTime, role, EventKind.NORMAL, null);
    }

    /** 纠错用量写入（仅 BillService 管理员纠错路径内部调用）。 */
    public IngestResult ingestCorrection(String eventId, String accountId, String metric,
                                         long signedQuantity, Instant eventTime,
                                         String correctionId) {
        return doIngest(eventId, accountId, metric, signedQuantity, eventTime,
                OperatorRole.ADMIN, EventKind.CORRECTION, correctionId);
    }

    private IngestResult doIngest(String eventId, String accountId, String metric,
                                  long quantity, Instant eventTime, OperatorRole role,
                                  EventKind kind, String correctionId) {
        validate(eventId, accountId, metric, quantity, eventTime, role, kind);
        String period = Periods.of(eventTime);
        log.info("ingest begin eventId={} account={} metric={} qty={} eventTime={} period={} role={} kind={}",
                eventId, accountId, metric, quantity, eventTime, period, role, kind);

        IngestResult result = periods.mutate(accountId, period, state -> {
            // 1) 事件级幂等：先查全局事件表
            UsageEvent existing = events.findById(eventId).orElse(null);
            if (existing != null) {
                if (!sameBusinessKey(existing, accountId, metric, quantity, eventTime, kind, correctionId)) {
                    log.warn("reject eventId={} reason=EVENT_ID_CONFLICT existing=(account={},metric={},qty={},time={},kind={})",
                            eventId, existing.accountId(), existing.metric(), existing.quantity(),
                            existing.eventTime(), existing.kind());
                    throw new BillingException(ErrorCode.EVENT_ID_CONFLICT,
                            "eventId already exists with different payload: " + eventId);
                }
                log.info("eventId={} duplicate, idempotent accept (already in period {})", eventId, period);
                return new IngestResult(true, period, existing);
            }

            // 2) 账期门禁（与受理在同一把账期锁内）
            checkGate(eventId, state, role, kind, period);

            // 3) 受理：先占账期内事件集合，再持久化事件（事件表 putIfAbsent 兜底）
            boolean accepted = state.accept(eventId);
            if (!accepted) {
                UsageEvent raced = events.findById(eventId).orElseThrow();
                log.info("eventId={} accepted by racing thread, idempotent accept", eventId);
                return new IngestResult(true, period, raced);
            }
            UsageEvent event = new UsageEvent(eventId, accountId, metric, quantity, eventTime,
                    Instant.now(clock), kind, correctionId);
            UsageEvent stored = events.saveIfAbsent(event, period);
            if (stored != event) {
                // 极端竞态：事件表已存在但集合内没有（理论上同锁不会发生），按幂等处理
                return new IngestResult(true, period, stored);
            }
            log.info("ingest accepted eventId={} period={} revision={}", eventId, period, state.getRevision());
            return new IngestResult(false, period, event);
        });
        return result;
    }

    private void checkGate(String eventId, PeriodState state, OperatorRole role,
                           EventKind kind, String period) {
        PeriodStatus status = state.getStatus();
        if (status == PeriodStatus.OPEN) {
            return;
        }
        if (status == PeriodStatus.REOPENED) {
            if (role == OperatorRole.ADMIN && kind == EventKind.CORRECTION) {
                log.info("eventId={} allowed into REOPENED period {} via admin correction path", eventId, period);
                return;
            }
            log.warn("reject eventId={} reason=PERIOD_CLOSED status=REOPENED role={} kind={} (reopened period is correction-only)",
                    eventId, role, kind);
            throw new BillingException(ErrorCode.PERIOD_CLOSED,
                    "period " + period + " is closed for normal writes (reopened: corrections only)");
        }
        // CLOSED
        log.warn("reject eventId={} reason=PERIOD_CLOSED status=CLOSED role={} kind={}", eventId, role, kind);
        throw new BillingException(ErrorCode.PERIOD_CLOSED,
                "period " + period + " is closed; event time " + eventId + " falls in a closed period");
    }

    private static boolean sameBusinessKey(UsageEvent e, String accountId, String metric, long quantity,
                                           Instant eventTime, EventKind kind, String correctionId) {
        return e.accountId().equals(accountId)
                && e.metric().equals(metric)
                && e.quantity() == quantity
                && e.eventTime().equals(eventTime)
                && e.kind() == kind
                && java.util.Objects.equals(e.correctionId(), correctionId);
    }

    private void validate(String eventId, String accountId, String metric, long quantity,
                          Instant eventTime, OperatorRole role, EventKind kind) {
        if (eventId == null || eventId.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "eventId is required");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "accountId is required");
        }
        if (metric == null || metric.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "metric is required");
        }
        if (eventTime == null) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "eventTime is required");
        }
        if (eventTime.isAfter(Instant.now(clock).plus(MAX_CLOCK_SKEW))) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "eventTime is too far in the future");
        }
        if (kind == EventKind.NORMAL && quantity <= 0) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "normal event quantity must be positive");
        }
        if (kind == EventKind.CORRECTION && quantity == 0) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "correction quantity must be non-zero");
        }
        if (role == null) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "role is required");
        }
    }

    public UsageEvent get(String eventId) {
        return events.findById(eventId)
                .orElseThrow(() -> new BillingException(ErrorCode.EVENT_NOT_FOUND,
                        "event not found: " + eventId));
    }

    public List<UsageEvent> list(String accountId, String period) {
        if (accountId == null || period == null) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "accountId and period are required");
        }
        return events.findByAccountAndPeriod(accountId, period);
    }

    /** 写入结果：duplicate=true 表示同一事件重复提交，按幂等成功返回。 */
    public record IngestResult(boolean duplicate, String period, UsageEvent event) {
    }
}
