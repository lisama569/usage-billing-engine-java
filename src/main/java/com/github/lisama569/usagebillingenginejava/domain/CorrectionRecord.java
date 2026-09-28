package com.github.lisama569.usagebillingenginejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 人工纠错流水。correctionId 唯一，保证纠错重试不重复生效；
 * 仅 ADMIN 可发起，且仅允许在 REOPENED 账期上执行。
 */
@Entity
@Table(name = "correction_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_correction_id", columnNames = "correction_id"))
public class CorrectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "correction_id", nullable = false, length = 128)
    private String correctionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "correction_type", nullable = false, length = 16)
    private CorrectionType correctionType;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 7)
    private String period;

    /** BACKFILL 时为新事件 id；REVERSAL 时为被冲正事件 id */
    @Column(name = "target_event_id", nullable = false, length = 128)
    private String targetEventId;

    @Column(name = "operator", nullable = false, length = 64)
    private String operator;

    @Column(nullable = false, length = 512)
    private String reason;

    @Column(name = "bill_version_after")
    private Integer billVersionAfter;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    public Long getId() { return id; }
    public String getCorrectionId() { return correctionId; }
    public void setCorrectionId(String correctionId) { this.correctionId = correctionId; }
    public CorrectionType getCorrectionType() { return correctionType; }
    public void setCorrectionType(CorrectionType correctionType) { this.correctionType = correctionType; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getTargetEventId() { return targetEventId; }
    public void setTargetEventId(String targetEventId) { this.targetEventId = targetEventId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getBillVersionAfter() { return billVersionAfter; }
    public void setBillVersionAfter(Integer billVersionAfter) { this.billVersionAfter = billVersionAfter; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
