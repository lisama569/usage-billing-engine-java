package com.github.lisama569.usagebillingenginejava.service;

import com.github.lisama569.usagebillingenginejava.domain.BillingPeriod;
import com.github.lisama569.usagebillingenginejava.domain.PeriodLock;
import com.github.lisama569.usagebillingenginejava.domain.UsageEvent;
import com.github.lisama569.usagebillingenginejava.repo.BillingPeriodRepository;
import com.github.lisama569.usagebillingenginejava.repo.PeriodLockRepository;
import com.github.lisama569.usagebillingenginejava.repo.UsageEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 事务边界与账期命名锁。
 *
 * 写入与关账都在“持有 period_lock 行悲观写锁”的单个事务内完成：
 * 写入事务 = 加命名锁 + 读账期状态 + 插入 ACTIVE 事件，提交即原子可见；
 * 关账事务 = 加命名锁 + 读全部 ACTIVE 事件出账 + 置 CLOSED。
 * 因此“返回 200 接受”与“FINAL 账单包含该事件”严格等价，不存在在途窗口。
 */
@Component
public class TxActions {

    private static final Logger log = LoggerFactory.getLogger(TxActions.class);

    private final BillingPeriodRepository periodRepo;
    private final UsageEventRepository eventRepo;
    private final PeriodLockRepository lockRepo;
    private final TransactionTemplate requiresNew;

    public TxActions(BillingPeriodRepository periodRepo,
                     UsageEventRepository eventRepo,
                     PeriodLockRepository lockRepo,
                     PlatformTransactionManager txManager) {
        this.periodRepo = periodRepo;
        this.eventRepo = eventRepo;
        this.lockRepo = lockRepo;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static String lockKey(String accountId, String period) {
        return accountId + "|" + period;
    }

    /** 确保命名锁行存在（原子 MERGE，天然幂等，不依赖约束异常）。 */
    public void ensureLockRow(String accountId, String period) {
        requiresNew.executeWithoutResult(status -> lockRepo.mergeLock(lockKey(accountId, period)));
    }

    /**
     * 确保账期行存在（持命名锁 + 原子 MERGE）。
     * MERGE 只在行缺失时插入 OPEN 默认值，已存在时不受影响。
     */
    public void ensurePeriodRow(String accountId, String period) {
        requiresNew.executeWithoutResult(status -> {
            lockRepo.lock(lockKey(accountId, period)).orElseThrow();
            periodRepo.mergeOpenPeriod(accountId, period);
        });
    }

    /**
     * 写入事务（REQUIRES_NEW，持命名锁）：账期必须已由 ensurePeriodRow 创建。
     * 锁内读状态：非 OPEN 抛 PeriodNotOpenException（事件不插入）；
     * eventId 撞键由唯一约束抛出 DataIntegrityViolationException（事务回滚）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertEventUnderLock(UsageEvent event) {
        lockRepo.lock(lockKey(event.getAccountId(), event.getPeriod()))
                .orElseThrow(() -> new IllegalStateException("锁行未初始化"));
        BillingPeriod period = periodRepo
                .findByAccountIdAndPeriod(event.getAccountId(), event.getPeriod())
                .orElseThrow(() -> new IllegalStateException("账期行未初始化"));
        if (period.getStatus() != com.github.lisama569.usagebillingenginejava.domain.BillingPeriodStatus.OPEN) {
            throw new PeriodNotOpenException(period.getStatus().name());
        }
        event.setState("ACTIVE");
        eventRepo.saveAndFlush(event);
    }

    /**
     * 管理事务（关账/重开/重算/纠错）：确保锁行后，在独立新事务中持命名锁执行动作。
     * 命名锁串行化同一账期的所有结构变更；账期行由动作内 insert-if-not-exists 保证存在。
     */
    public <T> T runLocked(String accountId, String period, Supplier<T> action) {
        ensureLockRow(accountId, period);
        return requiresNew.execute(status -> {
            lockRepo.lock(lockKey(accountId, period)).orElseThrow();
            return action.get();
        });
    }

    /** 非锁的只读查询。 */
    @Transactional(readOnly = true)
    public UsageEvent findEvent(String eventId) {
        return eventRepo.findByEventId(eventId).orElse(null);
    }

    /** 账期非 OPEN 时在锁内抛出的内部信号（普通写入被拒绝原因可区分）。 */
    public static class PeriodNotOpenException extends RuntimeException {
        private final String status;

        public PeriodNotOpenException(String status) {
            super("period status=" + status);
            this.status = status;
        }

        public String getStatus() {
            return status;
        }
    }
}
