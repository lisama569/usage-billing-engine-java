package com.github.lisama569.usagebillingenginejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 账期。按 UTC 自然月划分，一个账户一个账期只有一行（唯一键 account+period）。
 * 状态流转：OPEN -> CLOSED -> REOPENED -> CLOSED。
 */
@Entity
@Table(name = "billing_period",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uk_period_account_period", columnNames = {"account_id", "period"}),
        indexes = @Index(name = "idx_period_account", columnList = "account_id"))
public class BillingPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    /** 账期键，格式 yyyy-MM（UTC） */
    @Column(nullable = false, length = 7)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BillingPeriodStatus status = BillingPeriodStatus.OPEN;

    @Column(name = "closed_by", length = 64)
    private String closedBy;

    @Column(name = "closed_at")
    private Long closedAtEpochMs;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount = 0;

    /** JPA 乐观锁，保证 OPEN->CLOSED->REOPENED 状态流转无丢失更新 */
    @Version
    private Long lockVersion;

    public Long getId() { return id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BillingPeriodStatus getStatus() { return status; }
    public void setStatus(BillingPeriodStatus status) { this.status = status; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String closedBy) { this.closedBy = closedBy; }
    public Long getClosedAtEpochMs() { return closedAtEpochMs; }
    public void setClosedAtEpochMs(Long closedAtEpochMs) { this.closedAtEpochMs = closedAtEpochMs; }
    public int getReopenCount() { return reopenCount; }
    public void setReopenCount(int reopenCount) { this.reopenCount = reopenCount; }
    public Long getLockVersion() { return lockVersion; }
}
