package com.example.personareport.report.job;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "report_job_step")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportJobStep extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobId;

    @Column(nullable = false, length = 60)
    private String stepKey;

    @Column(nullable = false)
    private int stepOrder;

    @Column(nullable = false, length = 120)
    private String stepName;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false)
    private int attemptCount;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public static ReportJobStep create(Long jobId, String stepKey, int stepOrder, String stepName) {
        ReportJobStep step = new ReportJobStep();
        step.jobId = jobId;
        step.stepKey = stepKey;
        step.stepOrder = stepOrder;
        step.stepName = stepName;
        step.status = ReportJobStepStatus.PENDING;
        step.attemptCount = 0;
        return step;
    }

    public void running() {
        this.status = ReportJobStepStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.completedAt = null;
        this.errorMessage = null;
        this.attemptCount++;
    }

    public void skipped() {
        this.status = ReportJobStepStatus.SKIPPED;
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
        this.completedAt = LocalDateTime.now();
        this.errorMessage = null;
    }

    public void completed() {
        this.status = ReportJobStepStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = null;
    }

    public void stopped(String message) {
        this.status = ReportJobStepStatus.STOPPED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = message;
    }

    public void failed(String message) {
        this.status = ReportJobStepStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = message;
    }
}
