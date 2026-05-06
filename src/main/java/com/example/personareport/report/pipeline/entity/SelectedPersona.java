package com.example.personareport.report.pipeline.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "selected_persona")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SelectedPersona {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "report_order_id", nullable = false)
    private Long reportOrderId;
    @Column(name = "target_profile_id")
    private Long targetProfileId;
    @Column(name = "persona_profile_id", nullable = false)
    private Long personaProfileId;
    private Integer selectionRank;
    private String selectionGroup;
    private BigDecimal relevanceScore;
    private BigDecimal diversityScore;
    private BigDecimal finalScore;
    @Column(columnDefinition = "TEXT") private String selectionReason;
    private String personaScoreModelVersion;

    public static SelectedPersona create(Long orderId, Long targetProfileId, Long personaId,
                                          int rank, String group, String reason, String modelVersion) {
        var s = new SelectedPersona();
        s.reportOrderId = orderId; s.targetProfileId = targetProfileId; s.personaProfileId = personaId;
        s.selectionRank = rank; s.selectionGroup = group;
        s.relevanceScore = new BigDecimal("70"); s.diversityScore = new BigDecimal("60");
        s.finalScore = new BigDecimal("65"); s.selectionReason = reason;
        s.personaScoreModelVersion = modelVersion;
        return s;
    }
}
