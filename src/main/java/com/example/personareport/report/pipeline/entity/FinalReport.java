package com.example.personareport.report.pipeline.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "final_report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinalReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "report_order_id", nullable = false)
    private Long reportOrderId;
    private String reportVersion;
    private String responseVersion;
    private String modelName;
    private String modelVersion;
    private int responseCount;
    private int overallPurchaseIntentScore;
    private int overallTargetFitScore;
    private int overallPriceAcceptanceScore;
    private int overallTrustScore;
    private int overallDetailPageClarityScore;
    @Column(columnDefinition = "TEXT") private String finalVerdict;
    @Column(columnDefinition = "TEXT") private String executiveSummary;
    @Column(columnDefinition = "TEXT") private String purchaseIntentSummary;
    @Column(columnDefinition = "TEXT") private String priceSummary;
    @Column(columnDefinition = "TEXT") private String trustSummary;
    @Column(columnDefinition = "TEXT") private String targetValidationSummary;
    @Column(columnDefinition = "TEXT") private String segmentSummary;
    @Column(columnDefinition = "TEXT") private String improvementSummary;
    @Column(columnDefinition = "TEXT") private String riskSummary;
    @Column(columnDefinition = "TEXT") private String reportMarkdown;

    public static FinalReport create(Long orderId, int responseCount, int purchase, int fit, int price,
                                      int trust, int clarity, String verdict, String summary,
                                      String purchaseSummary, String priceSummary, String trustSummary,
                                      String targetSummary, String segmentSummary, String improvementSummary,
                                      String riskSummary, String markdown) {
        var r = new FinalReport();
        r.reportOrderId = orderId; r.responseCount = responseCount;
        r.overallPurchaseIntentScore = purchase; r.overallTargetFitScore = fit;
        r.overallPriceAcceptanceScore = price; r.overallTrustScore = trust;
        r.overallDetailPageClarityScore = clarity;
        r.finalVerdict = verdict; r.executiveSummary = summary;
        r.purchaseIntentSummary = purchaseSummary; r.priceSummary = priceSummary;
        r.trustSummary = trustSummary; r.targetValidationSummary = targetSummary;
        r.segmentSummary = segmentSummary; r.improvementSummary = improvementSummary;
        r.riskSummary = riskSummary; r.reportMarkdown = markdown;
        r.reportVersion = "detail_page_report_v1"; r.responseVersion = "detail_page_reaction_v1";
        r.modelName = "deepseek-v4-flash"; r.modelVersion = "v1";
        return r;
    }
}
