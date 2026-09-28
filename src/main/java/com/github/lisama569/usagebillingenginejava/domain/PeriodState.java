package com.github.lisama569.usagebillingenginejava.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 账期聚合状态。所有变更都在周期键的分段锁内完成（ConcurrentHashMap.compute），
 * acceptedEventIds 记录本周期已受理事件，保证并发下不丢不重。
 */
public final class PeriodState {
    private final String accountId;
    private final String period;
    private PeriodStatus status;
    private long revision;
    private Instant statusChangedAt;
    private String reopenReason;
    private int reopenCount;
    private final Set<String> acceptedEventIds = new LinkedHashSet<>();

    public PeriodState(String accountId, String period) {
        this.accountId = accountId;
        this.period = period;
        this.status = PeriodStatus.OPEN;
        this.revision = 0;
        this.statusChangedAt = Instant.EPOCH;
    }

    public String getAccountId() { return accountId; }
    public String getPeriod() { return period; }
    public PeriodStatus getStatus() { return status; }
    public long getRevision() { return revision; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
    public String getReopenReason() { return reopenReason; }
    public int getReopenCount() { return reopenCount; }
    public Set<String> getAcceptedEventIds() { return Collections.unmodifiableSet(acceptedEventIds); }

    public boolean isAccepted(String eventId) {
        return acceptedEventIds.contains(eventId);
    }

    /** 受理一条事件；返回 false 表示该事件此前已受理（重复提交）。 */
    public boolean accept(String eventId) {
        boolean added = acceptedEventIds.add(eventId);
        if (added) {
            revision++;
        }
        return added;
    }

    /** OPEN 或 REOPENED -> CLOSED；重复关闭抛状态冲突。 */
    public void close() {
        if (status == PeriodStatus.CLOSED) {
            throw new BillingException(ErrorCode.PERIOD_STATE_CONFLICT,
                    "period already closed: " + period);
        }
        status = PeriodStatus.CLOSED;
        revision++;
        statusChangedAt = Instant.now();
    }

    /** CLOSED -> REOPENED（受控重开，必须给原因）；非关闭态重开抛状态冲突。 */
    public void reopen(String reason) {
        if (status != PeriodStatus.CLOSED) {
            throw new BillingException(ErrorCode.PERIOD_STATE_CONFLICT,
                    "period is not closed, cannot reopen: " + period);
        }
        if (reason == null || reason.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "reopen reason is required");
        }
        status = PeriodStatus.REOPENED;
        reopenCount++;
        reopenReason = reason;
        revision++;
        statusChangedAt = Instant.now();
    }
}
