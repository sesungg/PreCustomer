package com.example.personareport.report.job;

import java.time.Duration;
import java.util.Optional;

public interface ReportJobQueue {

    ReportJob enqueueDetailPageReport(Long orderId, boolean forceRegenerate, boolean hasImages);

    boolean hasActiveJob(Long orderId);

    Optional<ReportJob> findLatestForOrder(Long orderId);

    Optional<ReportJob> claimNextJob(String workerId, Duration leaseDuration);

    boolean requestCancelForOrder(Long orderId);
}
