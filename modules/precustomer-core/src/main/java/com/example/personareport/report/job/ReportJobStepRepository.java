package com.example.personareport.report.job;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportJobStepRepository extends JpaRepository<ReportJobStep, Long> {

    long countByJobId(Long jobId);

    Optional<ReportJobStep> findByJobIdAndStepKey(Long jobId, String stepKey);
}
