package com.example.personareport.report.job;

import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.domain.PipelineProgress;
import com.example.personareport.report.service.ImageStorageService;
import com.example.personareport.report.service.PipelineProgressService;
import com.example.personareport.report.service.ReportPipelineService;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.pipeline", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class ReportJobWorker {

    private final ReportJobService jobService;
    private final ReportJobFailureClassifier failureClassifier;
    private final ReportPipelineService reportPipelineService;
    private final PipelineProgressService progressService;
    private final OrderService orderService;
    private final ImageStorageService imageStorageService;

    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();

    @Value("${app.pipeline.worker-lease-minutes:240}")
    private long leaseMinutes;

    @Value("${app.pipeline.retry-base-delay-seconds:30}")
    private long retryBaseDelaySeconds;

    @Value("${app.pipeline.retry-max-delay-seconds:300}")
    private long retryMaxDelaySeconds;

    @Scheduled(
            fixedDelayString = "${app.pipeline.worker-poll-ms:3000}",
            initialDelayString = "${app.pipeline.worker-initial-delay-ms:0}")
    public void poll() {
        Duration lease = Duration.ofMinutes(leaseMinutes);
        jobService.claimNextJob(workerId, lease)
                .ifPresent(job -> execute(job, lease));
    }

    private void execute(ReportJob job, Duration lease) {
        if (job.isCancelRequested()) {
            jobService.stop(job.getId(), "사용자 요청으로 리포트 작업을 시작하지 않고 중지했습니다.");
            return;
        }

        log.info("[report-job] claimed jobId={}, orderId={}, regenerate={}",
                job.getId(), job.getReportOrderId(), job.isForceRegenerate());
        try {
            jobService.heartbeat(job.getId(), lease);
            var order = orderService.getOrder(job.getReportOrderId());
            List<Path> imagePaths = imageStorageService.resolvePaths(order.getImagePaths());
            String status = reportPipelineService.executeDetailPagePipeline(
                    job.getReportOrderId(), imagePaths, job.isForceRegenerate(), job.getId(), lease);
            if (PipelineProgress.STATUS_COMPLETED.equals(status)) {
                jobService.complete(job.getId());
            } else if (PipelineProgress.STATUS_STOPPED.equals(status)) {
                jobService.stop(job.getId(), "사용자 요청으로 리포트 작업이 중지되었습니다.");
            } else {
                handleFailure(job, null, "리포트 파이프라인이 실패 상태로 종료되었습니다.");
            }
        } catch (Exception e) {
            log.error("[report-job] failed jobId={}, orderId={}: {}",
                    job.getId(), job.getReportOrderId(), e.getMessage(), e);
            handleFailure(job, e, e.getMessage());
        }
    }

    private void handleFailure(ReportJob job, Throwable error, String fallbackMessage) {
        String message = resolveFailureMessage(job.getReportOrderId(), fallbackMessage);
        ReportJobFailureClassifier.Failure failure = failureClassifier.classify(error, message);
        Duration delay = retryDelayFor(job.getAttemptCount());
        boolean retryScheduled = jobService.retryOrFail(
                job.getId(), failure.type(), message, failure.retryable(), delay);

        if (retryScheduled) {
            orderService.markGenerating(job.getReportOrderId());
            log.warn("[report-job] retry scheduled jobId={}, orderId={}, attempt={}/{}, failureType={}, retryInSeconds={}",
                    job.getId(), job.getReportOrderId(), job.getAttemptCount(), job.getMaxAttempts(),
                    failure.type(), delay.toSeconds());
            return;
        }
        log.warn("[report-job] terminal failure jobId={}, orderId={}, attempt={}/{}, failureType={}, retryable={}",
                job.getId(), job.getReportOrderId(), job.getAttemptCount(), job.getMaxAttempts(),
                failure.type(), failure.retryable());
    }

    private String resolveFailureMessage(Long orderId, String fallbackMessage) {
        return progressService.findById(orderId)
                .map(PipelineProgress::getErrorMessage)
                .filter(message -> message != null && !message.isBlank())
                .orElseGet(() -> fallbackMessage != null && !fallbackMessage.isBlank()
                        ? fallbackMessage
                        : "리포트 파이프라인 실행 중 알 수 없는 오류가 발생했습니다.");
    }

    private Duration retryDelayFor(int attemptCount) {
        long base = Math.max(0, retryBaseDelaySeconds);
        long max = Math.max(base, retryMaxDelaySeconds);
        int exponent = Math.max(0, Math.min(attemptCount - 1, 10));
        long multiplier = 1L << exponent;
        return Duration.ofSeconds(Math.min(max, base * multiplier));
    }
}
