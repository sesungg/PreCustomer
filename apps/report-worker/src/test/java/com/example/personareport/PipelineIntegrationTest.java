package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import com.example.personareport.order.domain.OrderStatus;
import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.domain.ReportPerspective;
import com.example.personareport.order.domain.TargetType;
import com.example.personareport.order.repository.ReactionReportOrderRepository;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.domain.PipelineProgress;
import com.example.personareport.report.pipeline.DeepSeekFeignClient;
import com.example.personareport.report.pipeline.PipelineJavaService;
import com.example.personareport.report.pipeline.PipelineQueryService;
import com.example.personareport.report.service.PipelineProgressService;
import com.example.personareport.report.service.ReportPipelineService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 파이프라인 통합 테스트. 외부 API(DeepSeek/Naver)는 Mock 처리. H2 인메모리 DB 사용. */
@ActiveProfiles("h2")
@SpringBootTest(properties = {
        "app.persona.import-enabled=false",
        "app.persona.sample-import-enabled=false"
})
class PipelineIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ReactionReportOrderRepository orderRepository;

    @Autowired
    private PipelineProgressService progressService;

    @Autowired
    private PipelineQueryService queryService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private DeepSeekFeignClient deepSeekClient;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @MockBean
    private ReportPipelineService reportPipeline;

    @Test
    void orderLifecycle_allStatusTransitions() {
        var order = saveOrder(
                "lifecycle@test.com", "라이프사이클", TargetType.ETC,
                "소개", "설명", null, "9,900원", "완전 무료배송",
                "타겟", "질문", ReportPerspective.GENERAL_REACTION, true
        );
        Long id = order.getId();

        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderStatus.REQUESTED);

        // REQUESTED → PAID
        orderService.markPaid(id);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderStatus.PAID);

        // PAID → FAILED
        orderService.markFailed(id);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderStatus.FAILED);

        // FAILED → COMPLETED (재생성 성공 시)
        orderService.markCompleted(id);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderStatus.COMPLETED);

        // COMPLETED → STOPPED (운영자가 중지 처리할 수 있음)
        orderService.markStopped(id);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderStatus.STOPPED);
    }

    @Test
    void getOrder_nonExistent_throwsException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.getOrder(99999L))
                .isInstanceOf(com.example.personareport.common.exception.ResourceNotFoundException.class);
    }

    @Test
    void pipelineProgress_createAndTransition() {
        // Create
        var progress = PipelineProgress.start(999L, 6);
        progressService.save(progress);
        Optional<PipelineProgress> found = progressService.findById(999L);
        assertThat(found).isPresent();
        assertThat(found.get().getCurrentStep()).isEqualTo(0);

        // Advance → Complete
        found.get().advanceStep("테스트 단계");
        progressService.save(found.get());
        found.get().complete();
        progressService.save(found.get());

        var completed = progressService.findById(999L);
        assertThat(completed).isPresent();
        assertThat(completed.get().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void pipelineProgress_stopRequestAndStoppedState() {
        var progress = PipelineProgress.start(996L, 5);
        progressService.save(progress);

        boolean accepted = progressService.requestStop(996L);
        var stopRequested = progressService.findById(996L);

        assertThat(accepted).isTrue();
        assertThat(stopRequested).isPresent();
        assertThat(stopRequested.get().getStatus()).isEqualTo(PipelineProgress.STATUS_STOP_REQUESTED);

        stopRequested.get().stop("사용자 요청으로 중지");
        progressService.save(stopRequested.get());

        var stopped = progressService.findById(996L);
        assertThat(stopped).isPresent();
        assertThat(stopped.get().getStatus()).isEqualTo(PipelineProgress.STATUS_STOPPED);
        assertThat(stopped.get().getErrorMessage()).contains("사용자 요청");
        assertThat(progressService.isStopRequested(996L)).isTrue();
    }

    @Test
    void pipelineProgress_stop_marksTerminalStoppedImmediately() {
        var progress = PipelineProgress.start(995L, 5);
        progress.advanceStep("최종 리포트 취합 중");
        progressService.save(progress);

        boolean accepted = progressService.stop(995L, "사용자 요청으로 중지");

        var stopped = progressService.findById(995L);
        assertThat(accepted).isTrue();
        assertThat(stopped).isPresent();
        assertThat(stopped.get().getStatus()).isEqualTo(PipelineProgress.STATUS_STOPPED);
        assertThat(stopped.get().isTerminal()).isTrue();
        assertThat(stopped.get().isActive()).isFalse();
        assertThat(progressService.isStopRequested(995L)).isTrue();
    }

    @Test
    void pipelineProgress_fail_recordsError() {
        var progress = PipelineProgress.start(998L, 5);
        progressService.save(progress);
        progress.fail("데이터 부족으로 실패");
        progressService.save(progress);

        var found = progressService.findById(998L);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo("FAILED");
        assertThat(found.get().getErrorMessage()).contains("데이터 부족");
    }

    @Test
    void pipelineProgress_resume_detectsFailedStep() {
        // Simulate failed pipeline
        var progress = PipelineProgress.start(997L, 6);
        progress.advanceStep("크롤링 중");
        progress.advanceStep("네이버 쇼핑 검색 중");
        progress.advanceStep("이미지 분석 중");
        progress.fail("타겟 프로필 생성 실패");
        progressService.save(progress);

        // Verify failed state
        var failed = progressService.findById(997L);
        assertThat(failed).isPresent();
        assertThat(failed.get().getStatus()).isEqualTo("FAILED");
        assertThat(failed.get().getCurrentStep()).isEqualTo(3); // Failed at step 3
    }

    @Test
    void shoppingAnalysisCompletion_acceptsEmptyMarketAnalysisSnapshot() {
        Long orderId = 994L;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS shopping_search_group (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    report_id BIGINT,
                    original_query VARCHAR(500) DEFAULT '',
                    candidate_count INTEGER DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS shopping_market_analysis_snapshot (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    search_group_id BIGINT,
                    price_analysis_json VARCHAR(1000)
                )
                """);

        jdbc.update("""
                INSERT INTO shopping_search_group (report_id, original_query, candidate_count)
                VALUES (?, ?, 0)
                """, orderId, "네이버 인증 실패 상품");
        Long groupId = jdbc.queryForObject(
                "SELECT id FROM shopping_search_group WHERE report_id = ?",
                Long.class,
                orderId
        );

        assertThat(queryService.hasReportShoppingAnalysis(orderId)).isFalse();

        jdbc.update("""
                INSERT INTO shopping_market_analysis_snapshot (search_group_id, price_analysis_json)
                VALUES (?, '{}')
                """, groupId);

        assertThat(queryService.hasReportShoppingAnalysis(orderId)).isTrue();
    }

    private ReactionReportOrder saveOrder(String email,
                                          String projectName,
                                          TargetType targetType,
                                          String oneLineDescription,
                                          String detailDescription,
                                          String pageUrl,
                                          String priceText,
                                          String shippingPolicyText,
                                          String targetCustomer,
                                          String mainQuestion,
                                          ReportPerspective reportPerspective,
                                          boolean privacyAgreement) {
        return orderRepository.saveAndFlush(ReactionReportOrder.create(
                email,
                projectName,
                targetType,
                oneLineDescription,
                detailDescription,
                pageUrl,
                priceText,
                shippingPolicyText,
                targetCustomer,
                mainQuestion,
                reportPerspective,
                privacyAgreement
        ));
    }
}
