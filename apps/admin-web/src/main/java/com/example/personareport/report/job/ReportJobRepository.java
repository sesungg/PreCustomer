package com.example.personareport.report.job;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportJobRepository extends JpaRepository<ReportJob, Long> {

    Optional<ReportJob> findFirstByReportOrderIdAndStatusInOrderByCreatedAtDesc(
            Long reportOrderId, Collection<String> statuses);

    Optional<ReportJob> findFirstByReportOrderIdOrderByCreatedAtDesc(Long reportOrderId);

    List<ReportJob> findByReportOrderIdAndStatusInOrderByCreatedAtDesc(
            Long reportOrderId, Collection<String> statuses);
}
