package com.example.personareport.report.pipeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "selected_persona")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SelectedPersona {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_order_id", nullable = false)
    private Long reportOrderId;

    @Column(name = "target_profile_id")
    private Long targetProfileId;

    @Column(name = "persona_profile_id", nullable = false)
    private Long personaProfileId;

    @Column(name = "selection_rank")
    private Integer selectionRank;

    @Column(name = "selection_group", length = 50)
    private String selectionGroup;

    @Column(name = "relevance_score", precision = 8, scale = 4)
    private BigDecimal relevanceScore;

    @Column(name = "diversity_score", precision = 8, scale = 4)
    private BigDecimal diversityScore;

    @Column(name = "final_score", precision = 8, scale = 4)
    private BigDecimal finalScore;

    @Column(name = "selection_reason", columnDefinition = "TEXT")
    private String selectionReason;

    @Column(name = "persona_score_model_version", length = 100)
    private String personaScoreModelVersion;
}
