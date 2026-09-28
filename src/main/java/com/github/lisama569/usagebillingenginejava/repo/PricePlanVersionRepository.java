package com.github.lisama569.usagebillingenginejava.repo;

import com.github.lisama569.usagebillingenginejava.domain.PricePlanVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PricePlanVersionRepository extends JpaRepository<PricePlanVersion, Long> {

    /** 命中 eventTime 的价格版本：validFrom <= t < validUntil(或当前版本 validUntil is null) */
    @Query("select v from PricePlanVersion v where v.accountId = :accountId and v.metric = :metric "
            + "and v.validFrom <= :at and (v.validUntil is null or v.validUntil > :at)")
    Optional<PricePlanVersion> findPriceAt(@Param("accountId") String accountId,
                                           @Param("metric") String metric,
                                           @Param("at") Long at);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PricePlanVersion v where v.accountId = :accountId and v.metric = :metric "
            + "and v.validUntil is null")
    Optional<PricePlanVersion> lockCurrentVersion(@Param("accountId") String accountId,
                                                  @Param("metric") String metric);

    List<PricePlanVersion> findByAccountIdAndMetricOrderByValidFromAsc(String accountId, String metric);
}
