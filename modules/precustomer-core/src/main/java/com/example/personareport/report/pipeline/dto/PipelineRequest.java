package com.example.personareport.report.pipeline.dto;

import lombok.Builder;
import lombok.Getter;

/** 파이프라인 실행 파라미터를 담는 DTO. 모든 파라미터에 기본값 있음. */
@Getter
@Builder
public class PipelineRequest {

    @Builder.Default
    private String profileVersion = "product_target_profile_v1";

    @Builder.Default
    private String responseVersion = "detail_page_reaction_v1";

    @Builder.Default
    private String reportVersion = "detail_page_final_report_v1";

    @Builder.Default
    private int selectedCount = 30;

    @Builder.Default
    private int candidateLimit = 150000;

    @Builder.Default
    private boolean resetSelected = false;

    @Builder.Default
    private int batchSize = 3;

    @Builder.Default
    private boolean skipExisting = false;

    @Builder.Default
    private String modelName = "deepseek";

    @Builder.Default
    private String modelVersion = "deepseek-v4-flash";

    @Builder.Default
    private double temperature = 0.4;

    @Builder.Default
    private int maxTokens = 9000;

    @Builder.Default
    private String thinkingMode = "disabled";
}
