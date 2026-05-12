package com.example.personareport.report.job;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "report_job")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportJob extends BaseTimeEntity {

    public static final String TYPE_DETAIL_PAGE_REPORT = "DETAIL_PAGE_REPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportOrderId;

    @Column(nullable = false, length = 50)
    private String jobType;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false)
    private boolean forceRegenerate;

    @Column(nullable = false)
    private boolean hasImages;

    @Column(nullable = false)
    private boolean cancelRequested;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private int maxAttempts;

    @Column(length = 120)
    private String lockedBy;

    private LocalDateTime lockedUntil;

    private LocalDateTime heartbeatAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Column(length = 50)
    private String failureType;

    private LocalDateTime nextRetryAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public static ReportJob detailPageReport(Long reportOrderId, boolean forceRegenerate, boolean hasImages) {
        ReportJob job = new ReportJob();
        job.reportOrderId = reportOrderId;
        job.jobType = TYPE_DETAIL_PAGE_REPORT;
        job.status = ReportJobStatus.PENDING;
        job.forceRegenerate = forceRegenerate;
        job.hasImages = hasImages;
        job.cancelRequested = false;
        job.attemptCount = 0;
        job.maxAttempts = 3;
        return job;
    }

    public boolean isActive() {
        return ReportJobStatus.ACTIVE.contains(status);
    }

    public boolean isCancelRequested() {
        return cancelRequested || ReportJobStatus.STOP_REQUESTED.equals(status);
    }

    public void requestCancel() {
        if (!isActive()) return;
        this.cancelRequested = true;
        this.status = ReportJobStatus.STOP_REQUESTED;
    }

    public void stop(String message) {
        this.status = ReportJobStatus.STOPPED;
        this.cancelRequested = true;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.nextRetryAt = null;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = message;
    }

    public void complete() {
        this.status = ReportJobStatus.COMPLETED;
        this.cancelRequested = false;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.nextRetryAt = null;
        this.completedAt = LocalDateTime.now();
        this.failureType = null;
        this.errorMessage = null;
    }

    public void fail(String message) {
        fail(message, "UNKNOWN");
    }

    public void fail(String message, String failureType) {
        this.status = ReportJobStatus.FAILED;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.nextRetryAt = null;
        this.completedAt = LocalDateTime.now();
        this.failureType = failureType;
        this.errorMessage = message;
    }

    public boolean canRetry() {
        return attemptCount < maxAttempts;
    }

    public void retryLater(String failureType, String message, Duration delay) {
        this.status = ReportJobStatus.PENDING;
        this.cancelRequested = false;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.heartbeatAt = null;
        this.completedAt = null;
        this.failureType = failureType;
        this.errorMessage = message;
        Duration safeDelay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
        this.nextRetryAt = LocalDateTime.now().plus(safeDelay);
    }
}
