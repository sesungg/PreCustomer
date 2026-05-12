package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.personareport.modules.shopping.service.ShoppingSearchService;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.domain.PipelineProgress;
import com.example.personareport.report.job.ReportJobService;
import com.example.personareport.report.pipeline.PipelineJavaService;
import com.example.personareport.report.pipeline.PipelineQueryService;
import com.example.personareport.report.pipeline.PipelineSaveService;
import com.example.personareport.report.service.PipelineProgressService;
import com.example.personareport.report.service.ReportPipelineService;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ReportPipelineService의 산출물 기반 재개 로직 테스트. */
@ExtendWith(MockitoExtension.class)
class ReportPipelineServiceResumeTest {

    @Mock
    private PipelineProgressService progressService;
    @Mock
    private PipelineJavaService pipelineJava;
    @Mock
    private PipelineQueryService queryService;
    @Mock
    private PipelineSaveService saveService;
    @Mock
    private ReportJobService jobService;
    @Mock
    private OrderService orderService;
    @Mock
    private ShoppingSearchService shoppingService;

    private ReportPipelineService service;

    @BeforeEach
    void setUp() {
        service = new ReportPipelineService(
                progressService, pipelineJava, queryService, saveService, jobService, orderService, shoppingService);
        lenient().when(progressService.save(any(PipelineProgress.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void runDetailPagePipeline_resumesFromMissingReactionsAndFinalReport() {
        Long orderId = 10L;
        String responseVersion = "detail_page_reaction_v1";
        String reportVersion = "detail_page_final_report_v1";
        String profileVersion = "product_target_profile_v1";

        when(progressService.findById(orderId)).thenReturn(Optional.empty());
        when(queryService.hasPageSnapshot(orderId)).thenReturn(true);
        when(queryService.hasReportShoppingAnalysis(orderId)).thenReturn(true);
        when(queryService.hasTargetProfile(orderId, profileVersion)).thenReturn(true);
        when(queryService.countSelectedPersonasForLatestTargetProfile(orderId, profileVersion)).thenReturn(30);
        when(queryService.hasCompleteReactions(orderId, responseVersion)).thenReturn(false, true);
        when(queryService.hasFinalReport(orderId, reportVersion)).thenReturn(false, true);

        service.runDetailPagePipeline(orderId, List.<Path>of());

        verify(pipelineJava, never()).generateTargetProfile(anyLong(), any());
        verify(pipelineJava, never()).selectPersonas(anyLong(), anyInt(), anyInt(), anyBoolean());
        verify(pipelineJava).generateReactions(eq(orderId), eq(responseVersion), eq(3), eq(true), any());
        verify(pipelineJava).generateFinalReport(orderId, responseVersion, reportVersion);
        verify(orderService).markCompleted(orderId);
    }

    @Test
    void requestStop_marksInProgressAsStoppedForOperator() {
        Long orderId = 11L;
        var progress = PipelineProgress.start(orderId, 8);

        when(progressService.findById(orderId)).thenReturn(Optional.of(progress));
        when(progressService.stop(eq(orderId), anyString())).thenAnswer(invocation -> {
            progress.stop(invocation.getArgument(1));
            return true;
        });

        boolean accepted = service.requestStop(orderId);

        assertThat(accepted).isTrue();
        assertThat(progress.getStatus()).isEqualTo(PipelineProgress.STATUS_STOPPED);
        verify(orderService).markStopped(orderId);
    }

    @Test
    void runDetailPagePipeline_doesNotStartWhenActiveProgressExists() {
        Long orderId = 12L;
        var progress = PipelineProgress.start(orderId, 8);

        when(progressService.findById(orderId)).thenReturn(Optional.of(progress));

        service.runDetailPagePipeline(orderId, List.<Path>of());

        verify(progressService, never()).save(any(PipelineProgress.class));
        verify(orderService, never()).markGenerating(anyLong());
        verify(pipelineJava, never()).generateReactions(anyLong(), anyString(), anyInt(), anyBoolean(), any());
    }

    @Test
    void regenerateDetailPagePipeline_clearsArtifactsThenRunsFromScratch() {
        Long orderId = 13L;
        String profileVersion = "product_target_profile_v1";
        String responseVersion = "detail_page_reaction_v1";
        String reportVersion = "detail_page_final_report_v1";

        when(progressService.findById(orderId)).thenReturn(Optional.empty());
        when(queryService.hasPageSnapshot(orderId)).thenReturn(true);
        when(queryService.hasReportShoppingAnalysis(orderId)).thenReturn(true);
        when(queryService.hasTargetProfile(orderId, profileVersion)).thenReturn(true);
        when(queryService.countSelectedPersonasForLatestTargetProfile(orderId, profileVersion)).thenReturn(30);
        when(queryService.hasCompleteReactions(orderId, responseVersion)).thenReturn(true);
        when(queryService.hasFinalReport(orderId, reportVersion)).thenReturn(true);

        service.regenerateDetailPagePipeline(orderId, List.<Path>of());

        verify(saveService).clearReportArtifacts(orderId);
        verify(orderService).markGenerating(orderId);
        verify(pipelineJava, never()).generateTargetProfile(anyLong(), anyString());
        verify(pipelineJava, never()).generateReactions(anyLong(), anyString(), anyInt(), anyBoolean(), any());
        verify(orderService).markCompleted(orderId);
    }
}
