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
 * 账单（带版本号的不可变快照，每次出账/重算追加新版本）。
 * amount 为该账期当前所有未冲正事件按各自命中价格版本计算的金额合计；
 * detailJson 保存逐条明细，保证历史结果可复现、可核对总量守恒。
 */
@Entity
@Table(name = "bill",
        uniqueConstraints = @UniqueConstraint(name = "uk_bill_version",
                columnNames = {"account_id", "period", "version"}),
        indexes = @Index(name = "idx_bill_account_period", columnList = "account_id,period"))
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable=false)
    private Integer version;

    /** DRAFT（OPEN/REOPENED 期间的试算）或 FINAL（关账冻结） */
    @Column(nullable = false, length = 8)
    private String status;

    /** 有效用量合计（未冲正事件数量之和） */
    @Column(name = "total_quantity", nullable = false)
    private Long totalQuantity;

    @Column(name = "reversed_quantity", nullable = false)
    private Long reversedQuantity;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "detail_json", nullable = false, length = 8192)
    private String detailJson;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    public Long getId() { return id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(Long totalQuantity) { this.totalQuantity = totalQuantity; }
    public Long getReversedQuantity() { return reversedQuantity; }
    public void setReversedQuantity(Long reversedQuantity) { this.reversedQuantity = reversedQuantity; }
    public Long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Long totalAmount) { this.totalAmount = totalAmount; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
