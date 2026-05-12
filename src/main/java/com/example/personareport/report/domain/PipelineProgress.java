package com.example.personareport.report.domain;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import java.time.Duration;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PipelineProgress extends BaseTimeEntity {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_STOP_REQUESTED = "STOP_REQUESTED";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int currentStep;

    @Column(nullable = false)
    private int totalSteps;

    @Column(length = 100)
    private String currentStepName;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime completedAt;

    private LocalDateTime stepStartedAt;

    public static PipelineProgress start(Long orderId, int totalSteps) {
        PipelineProgress p = new PipelineProgress();
        p.orderId = orderId;
        p.status = STATUS_IN_PROGRESS;
        p.currentStep = 0;
        p.totalSteps = totalSteps;
        p.currentStepName = "시작 중";
        p.errorMessage = null;
        p.completedAt = null;
        p.stepStartedAt = LocalDateTime.now();
        return p;
    }

    public void advanceStep(String stepName) {
        this.currentStep++;
        this.currentStepName = stepName;
        this.stepStartedAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = STATUS_COMPLETED;
        this.currentStep = this.totalSteps;
        this.currentStepName = "완료";
        this.errorMessage = null;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String errorMessage) {
        this.status = STATUS_FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public void requestStop() {
        if (isTerminal()) return;
        this.status = STATUS_STOP_REQUESTED;
        if (this.currentStepName == null || !this.currentStepName.startsWith("중지 요청됨")) {
            this.currentStepName = "중지 요청됨" + (this.currentStepName != null ? " - " + this.currentStepName : "");
        }
        this.errorMessage = null;
    }

    public void stop(String message) {
        this.status = STATUS_STOPPED;
        this.currentStepName = "중지됨";
        this.errorMessage = message;
        this.completedAt = LocalDateTime.now();
    }

    public boolean isInProgress() {
        return STATUS_IN_PROGRESS.equals(status);
    }

    public boolean isStopRequested() {
        return STATUS_STOP_REQUESTED.equals(status) || STATUS_STOPPED.equals(status);
    }

    public boolean isActive() {
        return isInProgress() || STATUS_STOP_REQUESTED.equals(status);
    }

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_STOPPED.equals(status);
    }

    public boolean isStale(Duration timeout) {
        if (!isActive() || stepStartedAt == null || timeout == null || timeout.isNegative() || timeout.isZero()) {
            return false;
        }
        return stepStartedAt.plus(timeout).isBefore(LocalDateTime.now());
    }
}
