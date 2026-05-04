package com.example.personareport.report.domain;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PipelineProgress extends BaseTimeEntity {

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

    public static PipelineProgress start(Long orderId, int totalSteps) {
        PipelineProgress p = new PipelineProgress();
        p.orderId = orderId;
        p.status = "IN_PROGRESS";
        p.currentStep = 0;
        p.totalSteps = totalSteps;
        p.currentStepName = "시작 중";
        return p;
    }

    public void advanceStep(String stepName) {
        this.currentStep++;
        this.currentStepName = stepName;
    }

    public void complete() {
        this.status = "COMPLETED";
        this.currentStep = this.totalSteps;
        this.currentStepName = "완료";
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String errorMessage) {
        this.status = "FAILED";
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }
}
