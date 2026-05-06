package com.example.personareport.report.pipeline.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "persona_reaction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonaReaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "report_order_id", nullable = false)
    private Long reportOrderId;
    @Column(name = "selected_persona_id")
    private Long selectedPersonaId;
    @Column(name = "persona_profile_id", nullable = false)
    private Long personaProfileId;
    private String segmentLabel;
    private String selectionGroup;
    private String sentiment;
    private String decisionStatus;
    private int purchaseIntentScore;
    private int targetFitScore;
    private int priceAcceptanceScore;
    private int trustScore;
    private int detailPageClarityScore;
    @Column(columnDefinition = "TEXT") private String firstImpression;
    @Column(columnDefinition = "TEXT") private String likelyReaction;
    @Column(columnDefinition = "TEXT") private String priceReaction;
    @Column(columnDefinition = "TEXT") private String trustReviewReaction;
    @Column(columnDefinition = "TEXT") private String detailPageFeedback;
    @Column(columnDefinition = "TEXT") private String representativeQuote;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String positivePoints;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String concerns;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String purchaseBarriers;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String recommendedDetailPageFixes;
    private String responseVersion;
    private String modelName;
    private String modelVersion;

    public static PersonaReaction create(Long orderId, Long selectedPersonaId, Long personaProfileId,
                                         String label, String group, String sentiment, String decision,
                                         int purchase, int fit, int price, int trust, int clarity,
                                         String firstImpression, String likelyReaction, String priceReaction,
                                         String trustReviewReaction, String detailPageFeedback,
                                         String representativeQuote, String positivePoints, String concerns,
                                         String purchaseBarriers, String fixes) {
        var r = new PersonaReaction();
        r.reportOrderId = orderId; r.selectedPersonaId = selectedPersonaId;
        r.personaProfileId = personaProfileId; r.segmentLabel = label; r.selectionGroup = group;
        r.sentiment = sentiment; r.decisionStatus = decision;
        r.purchaseIntentScore = purchase; r.targetFitScore = fit; r.priceAcceptanceScore = price;
        r.trustScore = trust; r.detailPageClarityScore = clarity;
        r.firstImpression = firstImpression; r.likelyReaction = likelyReaction;
        r.priceReaction = priceReaction; r.trustReviewReaction = trustReviewReaction;
        r.detailPageFeedback = detailPageFeedback; r.representativeQuote = representativeQuote;
        r.positivePoints = positivePoints; r.concerns = concerns;
        r.purchaseBarriers = purchaseBarriers; r.recommendedDetailPageFixes = fixes;
        r.responseVersion = "detail_page_reaction_v1"; r.modelName = "deepseek-v4-flash"; r.modelVersion = "v1";
        return r;
    }
}
