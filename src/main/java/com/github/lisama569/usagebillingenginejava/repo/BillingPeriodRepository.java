package com.github.lisama569.usagebillingenginejava.repo;

import com.github.lisama569.usagebillingenginejava.domain.BillingPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BillingPeriodRepository extends JpaRepository<BillingPeriod, Long> {

    Optional<BillingPeriod> findByAccountIdAndPeriod(String accountId, String period);

    /**
     * 原子首建 OPEN 账期行，仅在不存在时插入（已存在则 0 行受影响，状态不被覆盖）。
     * 调用方持有账期命名锁串行化，因此不会出现唯一键竞争。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            nativeQuery = true,
            value = "insert into billing_period "
                    + "(account_id, period, status, reopen_count, lock_version) "
                    + "select :accountId, :period, 'OPEN', 0, 0 "
                    + "where not exists ("
                    + "select 1 from billing_period where account_id = :accountId and period = :period)")
    void mergeOpenPeriod(@Param("accountId") String accountId, @Param("period") String period);
}
