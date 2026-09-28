package com.github.lisama569.usagebillingenginejava.repo;

import com.github.lisama569.usagebillingenginejava.domain.PeriodLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PeriodLockRepository extends JpaRepository<PeriodLock, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from PeriodLock l where l.lockKey = :key")
    Optional<PeriodLock> lock(@Param("key") String key);

    /** 原子插入锁行，已存在则无操作（H2 MERGE），避免靠约束异常建行。 */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            nativeQuery = true,
            value = "merge into period_lock (lock_key) key (lock_key) values (:key)")
    void mergeLock(@Param("key") String key);
}
