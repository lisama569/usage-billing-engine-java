package com.github.lisama569.usagebillingenginejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 价格版本（不可变）。同一 account+metric 下按 validFrom 形成首尾相接、
 * 无空隙无重叠的版本链：发布新版本时旧版本 validUntil 自动闭合。
 * 事件价格完全由 eventTime 命中的版本决定，因此跨周期/补录/重算结果可复现。
 */
@Entity
@Table(name = "price_plan_version",
        indexes = {
                @Index(name = "idx_plan_lookup", columnList = "account_id,metric,valid_from")
        })
public class PricePlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 64)
    private String metric;

    /** 单价（最小货币单位，整数） */
    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    /** 生效起点（含，epoch ms, UTC） */
    @Column(name = "valid_from", nullable = false)
    private Long validFrom;

    /** 生效终点（不含，epoch ms, UTC）；null 表示当前生效版本 */
    @Column(name = "valid_until")
    private Long validUntil;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Version
    private Long lockVersion;

    public Long getId() { return id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public Long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(Long unitPrice) { this.unitPrice = unitPrice; }
    public Long getValidFrom() { return validFrom; }
    public void setValidFrom(Long validFrom) { this.validFrom = validFrom; }
    public Long getValidUntil() { return validUntil; }
    public void setValidUntil(Long validUntil) { this.validUntil = validUntil; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getLockVersion() { return lockVersion; }
}
