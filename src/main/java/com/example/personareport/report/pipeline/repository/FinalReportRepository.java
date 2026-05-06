package com.example.personareport.report.pipeline.repository;

import com.example.personareport.report.pipeline.entity.FinalReport;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinalReportRepository extends JpaRepository<FinalReport, Long> {
    Optional<FinalReport> findFirstByReportOrderIdOrderByIdDesc(Long orderId);
    boolean existsByReportOrderId(Long orderId);
}
