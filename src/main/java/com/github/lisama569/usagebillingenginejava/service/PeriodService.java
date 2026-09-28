package com.github.lisama569.usagebillingenginejava.service;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.domain.PeriodState;
import com.github.lisama569.usagebillingenginejava.repository.PeriodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账期状态管理。
 * 状态机：OPEN -> CLOSED -> REOPENED -> CLOSED（可反复受控重开）。
 * close/reopen 均为 ADMIN 专属；重复关闭/对未关闭账期重开 => PERIOD_STATE_CONFLICT(409)，
 * 与"账期关闭拒绝写入"的 PERIOD_CLOSED(423) 区分。
 */
@Service
public class PeriodService {

    private static final Logger log = LoggerFactory.getLogger(PeriodService.class);

    private final PeriodRepository periods;

    public PeriodService(PeriodRepository periods) {
        this.periods = periods;
    }

    public PeriodState view(String accountId, String period) {
        return periods.view(accountId, period);
    }

    public PeriodState close(String accountId, String period) {
        PeriodState state = periods.mutate(accountId, period, s -> {
            if (s.getStatus() == com.github.lisama569.usagebillingenginejava.domain.PeriodStatus.CLOSED) {
                log.warn("reject close account={} period={} reason=PERIOD_STATE_CONFLICT (already closed)",
                        accountId, period);
                throw new BillingException(ErrorCode.PERIOD_STATE_CONFLICT,
                        "period already closed: " + period);
            }
            s.close();
            return s;
        });
        log.info("period closed account={} period={} revision={} acceptedEvents={}",
                accountId, period, state.getRevision(), state.getAcceptedEventIds().size());
        return state;
    }

    public PeriodState reopen(String accountId, String period, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "reopen reason is required for controlled reopen");
        }
        PeriodState state = periods.mutate(accountId, period, s -> {
            s.reopen(reason);
            return s;
        });
        log.warn("period REOPENED account={} period={} reason='{}' reopenCount={} revision={}",
                accountId, period, reason, state.getReopenCount(), state.getRevision());
        return state;
    }
}
