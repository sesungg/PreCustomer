package com.example.personareport.report.pipeline.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "product_target_profile")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductTargetProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "report_order_id", nullable = false)
    private Long reportOrderId;
    @Column(name = "profile_version")
    private String profileVersion;
    private String productCategory;
    private String productType;
    @Column(columnDefinition = "TEXT") private String targetSummary;
    @Column(columnDefinition = "jsonb") private String coreKeywords;
    @Column(columnDefinition = "jsonb") private String purchaseDrivers;
    @Column(columnDefinition = "jsonb") private String purchaseBarriers;
    @Column(columnDefinition = "jsonb") private String messageAngles;
    private String modelName;
    private String modelVersion;

    public static ProductTargetProfile create(Long orderId, String cat, String type, String summary,
                                               String keywords, String drivers, String barriers, String angles) {
        var p = new ProductTargetProfile();
        p.reportOrderId = orderId; p.profileVersion = "product_target_profile_v1";
        p.productCategory = cat; p.productType = type; p.targetSummary = summary;
        p.coreKeywords = keywords; p.purchaseDrivers = drivers;
        p.purchaseBarriers = barriers; p.messageAngles = angles;
        p.modelName = "deepseek-v4-flash"; p.modelVersion = "v1";
        return p;
    }
}
