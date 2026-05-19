package com.example.personareport.report.job;

import jakarta.persistence.EntityManager;
import com.example.personareport.report.event.ReportJobEventPublisher;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportJobService implements ReportJobQueue {

    private final ReportJobRepository jobRepository;
    private final ReportJobStepRepository stepRepository;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final ReportJobEventPublisher eventPublisher;

    @Override
    @Transactional
    public ReportJob enqueueDetailPageReport(Long orderId, boolean forceRegenerate, boolean hasImages) {
        var active = jobRepository.findFirstByReportOrderIdAndStatusInOrderByCreatedAtDesc(
                orderId, ReportJobStatus.ACTIVE);
        if (active.isPresent()) {
            return active.get();
        }
        ReportJob saved = jobRepository.save(ReportJob.detailPageReport(orderId, forceRegenerate, hasImages));
        eventPublisher.publishQueued(saved);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveJob(Long orderId) {
        return jobRepository.findFirstByReportOrderIdAndStatusInOrderByCreatedAtDesc(
                orderId, ReportJobStatus.ACTIVE).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReportJob> findLatestForOrder(Long orderId) {
        return jobRepository.findFirstByReportOrderIdOrderByCreatedAtDesc(orderId);
    }

    @Transactional(readOnly = true)
    public boolean isCancelRequested(Long jobId) {
        return jobRepository.findById(jobId)
                .map(ReportJob::isCancelRequested)
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean requestCancelForOrder(Long orderId) {
        List<ReportJob> jobs = jobRepository.findByReportOrderIdAndStatusInOrderByCreatedAtDesc(
                orderId, ReportJobStatus.ACTIVE);
        if (jobs.isEmpty()) return false;

        for (ReportJob job : jobs) {
            if (ReportJobStatus.PENDING.equals(job.getStatus())) {
                job.stop("사용자 요청으로 대기 중인 리포트 작업을 중지했습니다.");
            } else {
                job.requestCancel();
            }
        }
        jobRepository.saveAll(jobs);
        return true;
    }

    @Override
    @Transactional
    public Optional<ReportJob> claimNextJob(String workerId, Duration leaseDuration) {
        LocalDateTime lockedUntil = LocalDateTime.now().plus(leaseDuration);
        try {
            List<Long> ids = jdbc.queryForList("""
                WITH candidate AS (
                    SELECT id
                    FROM report_job
                    WHERE status = ?
                      AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP)
                      AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                    ORDER BY created_at ASC, id ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE report_job job
                SET status = ?,
                    locked_by = ?,
                    locked_until = ?,
                    heartbeat_at = CURRENT_TIMESTAMP,
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                    attempt_count = attempt_count + 1,
                    next_retry_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id
                """, Long.class,
                    ReportJobStatus.PENDING,
                    ReportJobStatus.RUNNING,
                    workerId,
                    Timestamp.valueOf(lockedUntil));
            if (ids.isEmpty()) return Optional.empty();
            entityManager.clear();
            return jobRepository.findById(ids.get(0));
        } catch (DataAccessException e) {
            log.debug("PostgreSQL SKIP LOCKED claim failed; falling back to simple claim. cause={}", e.getMessage());
            return claimNextJobFallback(workerId, lockedUntil);
        }
    }

    private Optional<ReportJob> claimNextJobFallback(String workerId, LocalDateTime lockedUntil) {
        List<Long> ids = jdbc.queryForList("""
            SELECT id
            FROM report_job
            WHERE status = ?
              AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP)
              AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
            ORDER BY created_at ASC, id ASC
            LIMIT 1
            """, Long.class, ReportJobStatus.PENDING);
        if (ids.isEmpty()) return Optional.empty();

        int updated = jdbc.update("""
            UPDATE report_job
            SET status = ?,
                locked_by = ?,
                locked_until = ?,
                heartbeat_at = CURRENT_TIMESTAMP,
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                attempt_count = attempt_count + 1,
                next_retry_at = NULL
            WHERE id = ?
              AND status = ?
            """,
                ReportJobStatus.RUNNING,
                workerId,
                Timestamp.valueOf(lockedUntil),
                ids.get(0),
                ReportJobStatus.PENDING);
        if (updated != 1) return Optional.empty();
        entityManager.clear();
        return jobRepository.findById(ids.get(0));
    }

    @Transactional
    public void heartbeat(Long jobId, Duration leaseDuration) {
        jdbc.update("""
            UPDATE report_job
            SET heartbeat_at = CURRENT_TIMESTAMP,
                locked_until = ?
            WHERE id = ?
              AND status IN (?, ?)
            """,
                Timestamp.valueOf(LocalDateTime.now().plus(leaseDuration)),
                jobId,
                ReportJobStatus.RUNNING,
                ReportJobStatus.STOP_REQUESTED);
    }

    @Transactional
    public void complete(Long jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.complete();
            ReportJob saved = jobRepository.save(job);
            eventPublisher.publishCompleted(saved);
        });
    }

    @Transactional
    public void stop(Long jobId, String message) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.stop(message);
            jobRepository.save(job);
        });
    }

    @Transactional
    public void fail(Long jobId, String message) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.fail(message);
            jobRepository.save(job);
        });
    }

    @Transactional
    public boolean retryOrFail(Long jobId, String failureType, String message, boolean retryable, Duration retryDelay) {
        return jobRepository.findById(jobId)
                .map(job -> {
                    boolean retryScheduled = retryable && job.canRetry();
                    if (retryScheduled) {
                        job.retryLater(failureType, message, retryDelay);
                    } else {
                        job.fail(message, failureType);
                    }
                    jobRepository.save(job);
                    return retryScheduled;
                })
                .orElse(false);
    }

    @Transactional
    public void prepareSteps(Long jobId, boolean hasImages) {
        if (stepRepository.countByJobId(jobId) > 0) return;
        List<ReportJobStep> steps = new ArrayList<>();
        int order = 1;
        steps.add(ReportJobStep.create(jobId, "crawl", order++,
                hasImages ? "상세페이지 캡처 분석 준비" : "URL 페이지 크롤링"));
        steps.add(ReportJobStep.create(jobId, "shopping", order++, "네이버 쇼핑 경쟁 상품 분석"));
        if (hasImages) {
            steps.add(ReportJobStep.create(jobId, "image_analysis", order++, "이미지 분석"));
        }
        steps.add(ReportJobStep.create(jobId, "target_profile", order++, "상품 타겟 프로필 생성"));
        steps.add(ReportJobStep.create(jobId, "persona_selection", order++, "페르소나 선별"));
        steps.add(ReportJobStep.create(jobId, "persona_reactions", order++, "페르소나 반응 생성"));
        steps.add(ReportJobStep.create(jobId, "final_report", order, "최종 리포트 취합"));
        stepRepository.saveAll(steps);
    }

    @Transactional
    public void markStepRunning(Long jobId, String stepKey) {
        stepRepository.findByJobIdAndStepKey(jobId, stepKey).ifPresent(step -> {
            step.running();
            stepRepository.save(step);
        });
    }

    @Transactional
    public void markStepSkipped(Long jobId, String stepKey) {
        stepRepository.findByJobIdAndStepKey(jobId, stepKey).ifPresent(step -> {
            step.skipped();
            stepRepository.save(step);
        });
    }

    @Transactional
    public void markStepCompleted(Long jobId, String stepKey) {
        stepRepository.findByJobIdAndStepKey(jobId, stepKey).ifPresent(step -> {
            step.completed();
            stepRepository.save(step);
        });
    }

    @Transactional
    public void markStepStopped(Long jobId, String stepKey, String message) {
        stepRepository.findByJobIdAndStepKey(jobId, stepKey).ifPresent(step -> {
            step.stopped(message);
            stepRepository.save(step);
        });
    }

    @Transactional
    public void markStepFailed(Long jobId, String stepKey, String message) {
        stepRepository.findByJobIdAndStepKey(jobId, stepKey).ifPresent(step -> {
            step.failed(message);
            stepRepository.save(step);
        });
    }
}
