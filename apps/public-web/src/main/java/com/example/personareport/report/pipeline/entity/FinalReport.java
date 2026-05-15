package com.example.personareport.report.pipeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "final_report")
@NoArgsConstructor
public class FinalReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_order_id", nullable = false)
    private Long reportOrderId;

    @Column(name = "product_target_profile_id")
    private Long productTargetProfileId;

    @Column(name = "page_snapshot_id")
    private Long pageSnapshotId;

    @Column(name = "report_version", length = 100)
    private String reportVersion;

    @Column(name = "response_version", length = 100)
    private String responseVersion;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "response_count", nullable = false)
    private int responseCount;

    @Column(name = "overall_purchase_intent_score")
    private Integer overallPurchaseIntentScore;

    @Column(name = "overall_target_fit_score")
    private Integer overallTargetFitScore;

    @Column(name = "overall_price_acceptance_score")
    private Integer overallPriceAcceptanceScore;

    @Column(name = "overall_trust_score")
    private Integer overallTrustScore;

    @Column(name = "overall_detail_page_clarity_score")
    private Integer overallDetailPageClarityScore;

    @Column(name = "final_verdict", length = 50)
    private String finalVerdict;

    @Column(name = "executive_summary", columnDefinition = "TEXT")
    private String executiveSummary;

    @Column(name = "target_validation_summary", columnDefinition = "TEXT")
    private String targetValidationSummary;

    @Column(name = "purchase_intent_summary", columnDefinition = "TEXT")
    private String purchaseIntentSummary;

    @Column(name = "price_summary", columnDefinition = "TEXT")
    private String priceSummary;

    @Column(name = "trust_summary", columnDefinition = "TEXT")
    private String trustSummary;

    @Column(name = "detail_page_summary", columnDefinition = "TEXT")
    private String detailPageSummary;

    @Column(name = "segment_summary", columnDefinition = "TEXT")
    private String segmentSummary;

    @Column(name = "improvement_summary", columnDefinition = "TEXT")
    private String improvementSummary;

    @Column(name = "risk_summary", columnDefinition = "TEXT")
    private String riskSummary;

    @Column(name = "report_markdown", columnDefinition = "TEXT")
    private String reportMarkdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", columnDefinition = "jsonb")
    private String reportJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aggregate_json", columnDefinition = "jsonb")
    private String aggregateJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;
}
