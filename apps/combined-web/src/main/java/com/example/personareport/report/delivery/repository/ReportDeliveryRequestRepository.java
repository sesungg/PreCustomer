package com.example.personareport.report.delivery.repository;

import com.example.personareport.report.delivery.domain.ReportDeliveryRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportDeliveryRequestRepository extends JpaRepository<ReportDeliveryRequest, Long> {

    Optional<ReportDeliveryRequest> findByReportOrderId(Long reportOrderId);
}
