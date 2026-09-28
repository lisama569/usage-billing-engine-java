package com.github.lisama569.usagebillingenginejava.repo;

import com.github.lisama569.usagebillingenginejava.domain.CorrectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CorrectionRecordRepository extends JpaRepository<CorrectionRecord, Long> {

    Optional<CorrectionRecord> findByCorrectionId(String correctionId);
}
