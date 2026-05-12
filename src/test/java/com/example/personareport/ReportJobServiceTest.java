package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import com.example.personareport.order.domain.ReportPerspective;
import com.example.personareport.order.domain.TargetType;
import com.example.personareport.order.dto.OrderRequest;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.job.ReportJobRepository;
import com.example.personareport.report.job.ReportJobService;
import com.example.personareport.report.job.ReportJobStatus;
import com.example.personareport.report.job.ReportJobStepRepository;
import com.example.personareport.report.job.ReportJobStepStatus;
import com.example.personareport.report.pipeline.DeepSeekFeignClient;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("h2")
@SpringBootTest(properties = {
        "app.persona.import-enabled=false",
        "app.persona.sample-import-enabled=false"
})
@Transactional
class ReportJobServiceTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ReportJobService jobService;

    @Autowired
    private ReportJobRepository jobRepository;

    @Autowired
    private ReportJobStepRepository stepRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private DeepSeekFeignClient deepSeekClient;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @Test
    void enqueueDetailPageReport_reusesActiveJobForSameOrder() {
        Long orderId = createOrder();

        var first = jobService.enqueueDetailPageReport(orderId, false, false);
        var second = jobService.enqueueDetailPageReport(orderId, true, true);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(jobRepository.findAll()).hasSize(1);
        assertThat(second.isForceRegenerate()).isFalse();
    }

    @Test
    void claimNextJob_marksPendingJobAsRunning() {
        Long orderId = createOrder();
        var job = jobService.enqueueDetailPageReport(orderId, false, false);

        var claimed = jobService.claimNextJob("test-worker", Duration.ofMinutes(5));

        assertThat(claimed).isPresent();
        assertThat(claimed.get().getId()).isEqualTo(job.getId());
        assertThat(claimed.get().getStatus()).isEqualTo(ReportJobStatus.RUNNING);
        assertThat(claimed.get().getAttemptCount()).isEqualTo(1);
        assertThat(claimed.get().getLockedBy()).isEqualTo("test-worker");
        assertThat(claimed.get().getLockedUntil()).isNotNull();
    }

    @Test
    void retryOrFail_requeuesRetryableFailureBeforeMaxAttempts() {
        Long orderId = createOrder();
        var job = jobService.enqueueDetailPageReport(orderId, false, false);
        var claimed = jobService.claimNextJob("test-worker", Duration.ofMinutes(5)).orElseThrow();

        boolean retryScheduled = jobService.retryOrFail(
                claimed.getId(),
                "DEEPSEEK_TRANSIENT",
                "DeepSeek 503 Service Unavailable",
                true,
                Duration.ofMinutes(1));

        var found = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(retryScheduled).isTrue();
        assertThat(found.getStatus()).isEqualTo(ReportJobStatus.PENDING);
        assertThat(found.getAttemptCount()).isEqualTo(1);
        assertThat(found.getFailureType()).isEqualTo("DEEPSEEK_TRANSIENT");
        assertThat(found.getErrorMessage()).contains("DeepSeek 503");
        assertThat(found.getLockedUntil()).isNull();
        assertThat(found.getNextRetryAt()).isNotNull();
        assertThat(jobService.claimNextJob("too-early-worker", Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void retryOrFail_marksTerminalFailureForNonRetryableFailure() {
        Long orderId = createOrder();
        var job = jobService.enqueueDetailPageReport(orderId, false, false);
        var claimed = jobService.claimNextJob("test-worker", Duration.ofMinutes(5)).orElseThrow();

        boolean retryScheduled = jobService.retryOrFail(
                claimed.getId(),
                "DATA_ERROR",
                "bad SQL grammar",
                false,
                Duration.ZERO);

        var found = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(retryScheduled).isFalse();
        assertThat(found.getStatus()).isEqualTo(ReportJobStatus.FAILED);
        assertThat(found.getFailureType()).isEqualTo("DATA_ERROR");
        assertThat(found.getCompletedAt()).isNotNull();
        assertThat(found.getNextRetryAt()).isNull();
    }

    @Test
    void retryOrFail_marksTerminalFailureWhenMaxAttemptsExhausted() {
        Long orderId = createOrder();
        var job = jobService.enqueueDetailPageReport(orderId, false, false);
        var claimed = jobService.claimNextJob("worker-1", Duration.ofMinutes(5)).orElseThrow();

        jdbc.update("UPDATE report_job SET attempt_count = max_attempts WHERE id = ?", job.getId());
        entityManager.clear();

        boolean retryScheduled = jobService.retryOrFail(
                claimed.getId(), "DEEPSEEK_TRANSIENT", "DeepSeek timeout", true, Duration.ZERO);

        var found = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(retryScheduled).isFalse();
        assertThat(found.getStatus()).isEqualTo(ReportJobStatus.FAILED);
        assertThat(found.getAttemptCount()).isEqualTo(3);
        assertThat(found.getFailureType()).isEqualTo("DEEPSEEK_TRANSIENT");
    }

    @Test
    void requestCancelForOrder_stopsPendingJobBeforeWorkerStarts() {
        Long orderId = createOrder();
        var job = jobService.enqueueDetailPageReport(orderId, false, false);

        boolean accepted = jobService.requestCancelForOrder(orderId);

        var found = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(accepted).isTrue();
        assertThat(found.getStatus()).isEqualTo(ReportJobStatus.STOPPED);
        assertThat(found.isCancelRequested()).isTrue();
    }

    @Test
    void prepareSteps_createsObservableReportSteps() {
        Long orderId = createOrder();
        var job = jobService.enqueueDetailPageReport(orderId, false, true);

        jobService.prepareSteps(job.getId(), true);
        jobService.markStepRunning(job.getId(), "persona_reactions");
        jobService.markStepCompleted(job.getId(), "persona_reactions");

        assertThat(stepRepository.countByJobId(job.getId())).isEqualTo(7);
        var step = stepRepository.findByJobIdAndStepKey(job.getId(), "persona_reactions").orElseThrow();
        assertThat(step.getStatus()).isEqualTo(ReportJobStepStatus.COMPLETED);
        assertThat(step.getAttemptCount()).isEqualTo(1);
    }

    private Long createOrder() {
        var request = new OrderRequest(
                "job-test@example.com",
                "잡 테스트 상품",
                TargetType.ETC,
                "소개",
                "상세 설명",
                null,
                "10,000원",
                "타겟",
                "질문",
                ReportPerspective.GENERAL_REACTION,
                true
        );
        return orderService.createOrder(request, null).getId();
    }
}
