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
@Table(name = "page_image_analysis")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageImageAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_order_id", nullable = false)
    private Long reportOrderId;

    @Column(name = "page_snapshot_id")
    private Long pageSnapshotId;

    @Column(name = "image_path", columnDefinition = "TEXT")
    private String imagePath;

    @Column(name = "image_role", length = 50)
    private String imageRole;

    @Column(name = "image_part_no")
    private Integer imagePartNo;

    @Column(name = "image_part_count")
    private Integer imagePartCount;

    @Column(name = "analysis_version", length = 100)
    private String analysisVersion;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "image_summary", columnDefinition = "TEXT")
    private String imageSummary;

    @Column(name = "visible_text", columnDefinition = "TEXT")
    private String visibleText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visual_trust_elements", columnDefinition = "jsonb")
    private String visualTrustElements;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visual_purchase_drivers", columnDefinition = "jsonb")
    private String visualPurchaseDrivers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visual_purchase_barriers", columnDefinition = "jsonb")
    private String visualPurchaseBarriers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_claims", columnDefinition = "jsonb")
    private String visibleClaims;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_prices", columnDefinition = "jsonb")
    private String visiblePrices;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_certifications", columnDefinition = "jsonb")
    private String visibleCertifications;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_usage_instructions", columnDefinition = "jsonb")
    private String visibleUsageInstructions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "design_feedback", columnDefinition = "jsonb")
    private String designFeedback;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "information_gaps", columnDefinition = "jsonb")
    private String informationGaps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "safety_or_compliance_notes", columnDefinition = "jsonb")
    private String safetyOrComplianceNotes;

    @Column(name = "raw_analysis", columnDefinition = "TEXT")
    private String rawAnalysis;
}
