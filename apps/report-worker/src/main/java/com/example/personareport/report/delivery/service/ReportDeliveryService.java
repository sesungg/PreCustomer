package com.example.personareport.report.delivery.service;

import com.example.personareport.report.delivery.domain.ReportDeliveryRequest;
import com.example.personareport.report.delivery.repository.ReportDeliveryRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportDeliveryService {

    private final ReportDeliveryRequestRepository repository;
    private final ReportDeliveryMailService mailService;

    @Transactional
    public ReportDeliveryRequest saveEmailOnly(Long orderId, String email) {
        ReportDeliveryRequest request = repository.findByReportOrderId(orderId)
                .orElseGet(() -> ReportDeliveryRequest.createEmailOnly(orderId, email));
        request.updateEmailOnly(email);
        return repository.save(request);
    }

    @Transactional
    public void deliverCompletedReport(Long orderId) {
        repository.findByReportOrderId(orderId).ifPresentOrElse(request -> {
            try {
                var result = mailService.sendCompletedReport(request);
                if (result.sent()) {
                    request.markSent();
                } else if (result.readyOnly()) {
                    request.markReady(result.message());
                } else {
                    request.markFailed(result.message());
                }
                repository.save(request);
            } catch (Exception e) {
                log.warn("report delivery failed orderId={}, email={}: {}",
                        orderId, request.getEmail(), e.getMessage());
                request.markFailed(e.getMessage());
                repository.save(request);
            }
        }, () -> log.info("report delivery skipped; no request orderId={}", orderId));
    }
}
