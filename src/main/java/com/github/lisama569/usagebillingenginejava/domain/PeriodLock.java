package com.github.lisama569.usagebillingenginejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 账期命名锁。主键为 accountId + '|' + period。
 * 写入的“预占→激活”整个窗口与关账都先取该行的悲观写锁，
 * 从而保证：关账读账单时不存在“已预占但尚未激活”的在途写入。
 */
@Entity
@Table(name = "period_lock")
public class PeriodLock {

    @Id
    @Column(name = "lock_key", length = 96)
    private String lockKey;

    protected PeriodLock() {
    }

    public PeriodLock(String lockKey) {
        this.lockKey = lockKey;
    }

    public String getLockKey() {
        return lockKey;
    }
}
