package com.github.lisama569.usagebillingenginejava.repo;

import com.github.lisama569.usagebillingenginejava.domain.Bill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillRepository extends JpaRepository<Bill, Long> {

    Optional<Bill> findFirstByAccountIdAndPeriodOrderByVersionDesc(String accountId, String period);

    List<Bill> findByAccountIdAndPeriodOrderByVersionAsc(String accountId, String period);
}
