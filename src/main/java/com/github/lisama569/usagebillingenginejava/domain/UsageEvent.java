package com.github.lisama569.usagebillingenginejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 用量事件。event_id 全局唯一，是幂等去重的唯一依据：
 * 唯一约束 uk_event_event_id 在数据库层兜底并发/重试下的重复提交。
 * 事件归属账期由 eventTime 所在 UTC 自然月决定，与接收顺序无关（支持乱序补录）。
 * reversed=true 表示该事件已被一次 REVERSAL 纠错冲正，不再参与计费；
 * 保留行不删除，以保证审计与总量守恒。
 */
@Entity
@Table(name = "usage_event",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_event_event_id", columnNames = "event_id"),
                @UniqueConstraint(name = "uk_event_correction", columnNames = "correction_id")
        },
        indexes = {
                @Index(name = "idx_event_account_period", columnList = "account_id,period"),
                @Index(name = "idx_event_metric", columnList = "metric")
        })
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 64)
    private String metric;

    @Column(nullable = false)
    private Long quantity;

    /** 用量实际发生时间（epoch ms, UTC） */
    @Column(name = "event_time", nullable = false)
    private Long eventTime;

    /** 服务端接收时间（epoch ms, UTC） */
    @Column(name = "received_at", nullable = false)
    private Long receivedAt;

    /** 归属账期 yyyy-MM，依据 eventTime 计算 */
    @Column(nullable = false, length = 7)
    private String period;

    /**
     * 写入生命周期，用于关账与写入并发下的严格线性化：
     * PENDING 已在账期锁内预占但尚未提交完成（不计费）；
     * ACTIVE 正常计入；REVERSED 被冲正（保留行，不计费）。
     */
    @Column(nullable = false, length = 16)
    private String state = "ACTIVE";

    @Column(nullable = false)
    private boolean reversed = false;

    @Column(name = "reversed_by", length = 64)
    private String reversedBy;

    /** 若来自纠错补录，记录 correctionId（普通写入为 null） */
    @Column(name = "correction_id", length = 128)
    private String correctionId;

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public Long getQuantity() { return quantity; }
    public void setQuantity(Long quantity) { this.quantity = quantity; }
    public Long getEventTime() { return eventTime; }
    public void setEventTime(Long eventTime) { this.eventTime = eventTime; }
    public Long getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Long receivedAt) { this.receivedAt = receivedAt; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public boolean isReversed() { return reversed; }
    public void setReversed(boolean reversed) { this.reversed = reversed; }
    public String getReversedBy() { return reversedBy; }
    public void setReversedBy(String reversedBy) { this.reversedBy = reversedBy; }
    public String getCorrectionId() { return correctionId; }
    public void setCorrectionId(String correctionId) { this.correctionId = correctionId; }
}
