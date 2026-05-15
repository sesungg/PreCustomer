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
@Table(name = "persona_reaction")
@NoArgsConstructor
public class PersonaReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_order_id", nullable = false)
    private Long reportOrderId;

    @Column(name = "selected_persona_id")
    private Long selectedPersonaId;

    @Column(name = "persona_profile_id", nullable = false)
    private Long personaProfileId;

    @Column(name = "product_target_profile_id")
    private Long productTargetProfileId;

    @Column(name = "response_version", length = 100)
    private String responseVersion;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "selection_group", length = 50)
    private String selectionGroup;

    @Column(name = "selection_rank")
    private Integer selectionRank;

    @Column(name = "segment_label", length = 200)
    private String segmentLabel;

    @Column(name = "sentiment", length = 30)
    private String sentiment;

    @Column(name = "decision_status", length = 50)
    private String decisionStatus;

    @Column(name = "purchase_intent_score", nullable = false)
    private int purchaseIntentScore;

    @Column(name = "target_fit_score", nullable = false)
    private int targetFitScore;

    @Column(name = "price_acceptance_score", nullable = false)
    private int priceAcceptanceScore;

    @Column(name = "trust_score", nullable = false)
    private int trustScore;

    @Column(name = "detail_page_clarity_score", nullable = false)
    private int detailPageClarityScore;

    @Column(name = "first_impression", columnDefinition = "TEXT")
    private String firstImpression;

    @Column(name = "likely_reaction", columnDefinition = "TEXT")
    private String likelyReaction;

    @Column(name = "price_reaction", columnDefinition = "TEXT")
    private String priceReaction;

    @Column(name = "trust_review_reaction", columnDefinition = "TEXT")
    private String trustReviewReaction;

    @Column(name = "detail_page_feedback", columnDefinition = "TEXT")
    private String detailPageFeedback;

    @Column(name = "representative_quote", columnDefinition = "TEXT")
    private String representativeQuote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "positive_points", columnDefinition = "jsonb")
    private String positivePoints;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "concerns", columnDefinition = "jsonb")
    private String concerns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_information", columnDefinition = "jsonb")
    private String missingInformation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "purchase_barriers", columnDefinition = "jsonb")
    private String purchaseBarriers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "persuasion_messages", columnDefinition = "jsonb")
    private String persuasionMessages;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommended_detail_page_fixes", columnDefinition = "jsonb")
    private String recommendedDetailPageFixes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;
}
