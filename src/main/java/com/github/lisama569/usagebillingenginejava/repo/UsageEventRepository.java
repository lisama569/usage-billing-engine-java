package com.github.lisama569.usagebillingenginejava.repo;

import com.github.lisama569.usagebillingenginejava.domain.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    Optional<UsageEvent> findByEventId(String eventId);

    List<UsageEvent> findByAccountIdAndPeriodOrderByEventTimeAsc(String accountId, String period);

    /** 关账/重算按状态取事件：ACTIVE 计价；REVERSED 单列冲正行 */
    List<UsageEvent> findByAccountIdAndPeriodAndStateOrderByEventTimeAsc(
            String accountId, String period, String state);
}
