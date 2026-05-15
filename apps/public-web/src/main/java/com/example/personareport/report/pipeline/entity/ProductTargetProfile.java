package com.example.personareport.report.pipeline.entity;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "product_target_profile")
@NoArgsConstructor
public class ProductTargetProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_order_id", nullable = false)
    private Long reportOrderId;

    @Column(name = "page_snapshot_id")
    private Long pageSnapshotId;

    @Column(name = "profile_version", length = 100)
    private String profileVersion;

    @Column(name = "product_category", length = 100)
    private String productCategory;

    @Column(name = "product_type", length = 100)
    private String productType;

    @Column(name = "product_name", columnDefinition = "TEXT")
    private String productName;

    @Column(name = "target_summary", columnDefinition = "TEXT")
    private String targetSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "core_keywords", columnDefinition = "jsonb")
    private String coreKeywords;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exclusion_keywords", columnDefinition = "jsonb")
    private String exclusionKeywords;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "purchase_drivers", columnDefinition = "jsonb")
    private String purchaseDrivers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "purchase_barriers", columnDefinition = "jsonb")
    private String purchaseBarriers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_hypotheses", columnDefinition = "jsonb")
    private String audienceHypotheses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "comparison_audiences", columnDefinition = "jsonb")
    private String comparisonAudiences;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection_weights", columnDefinition = "jsonb")
    private String selectionWeights;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "demographic_priors", columnDefinition = "jsonb")
    private String demographicPriors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sampling_strategy", columnDefinition = "jsonb")
    private String samplingStrategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_angles", columnDefinition = "jsonb")
    private String messageAngles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_focus_points", columnDefinition = "jsonb")
    private String reportFocusPoints;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_profile", columnDefinition = "jsonb")
    private String rawProfile;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "model_version", length = 100)
    private String modelVersion;
}
