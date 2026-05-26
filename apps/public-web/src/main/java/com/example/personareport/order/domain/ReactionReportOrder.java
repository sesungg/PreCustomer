package com.example.personareport.order.domain;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "report_order")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReactionReportOrder extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerEmail;

    private Long customerAccountId;

    @Column(nullable = false)
    private String projectName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetType targetType;

    @Column(columnDefinition = "TEXT")
    private String oneLineDescription;

    @Column(columnDefinition = "TEXT")
    private String detailDescription;

    private String pageUrl;

    private String priceText;

    @Column(columnDefinition = "TEXT")
    private String shippingPolicyText;

    @Column(columnDefinition = "TEXT")
    private String targetCustomer;

    @Column(columnDefinition = "TEXT")
    private String mainQuestion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportPerspective reportPerspective;

    @Column(nullable = false)
    private boolean privacyAgreement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(columnDefinition = "TEXT")
    private String imagePaths;

    private ReactionReportOrder(
            String customerEmail,
            String projectName,
            TargetType targetType,
            String oneLineDescription,
            String detailDescription,
            String pageUrl,
            String priceText,
            String shippingPolicyText,
            String targetCustomer,
            String mainQuestion,
            ReportPerspective reportPerspective,
            boolean privacyAgreement
    ) {
        this.customerEmail = customerEmail;
        this.projectName = projectName;
        this.targetType = targetType;
        this.oneLineDescription = oneLineDescription;
        this.detailDescription = detailDescription;
        this.pageUrl = pageUrl;
        this.priceText = priceText;
        this.shippingPolicyText = shippingPolicyText;
        this.targetCustomer = targetCustomer;
        this.mainQuestion = mainQuestion;
        this.reportPerspective = reportPerspective;
        this.privacyAgreement = privacyAgreement;
        this.status = OrderStatus.REQUESTED;
    }

    public static ReactionReportOrder create(
            String customerEmail,
            String projectName,
            TargetType targetType,
            String oneLineDescription,
            String detailDescription,
            String pageUrl,
            String priceText,
            String shippingPolicyText,
            String targetCustomer,
            String mainQuestion,
            ReportPerspective reportPerspective,
            boolean privacyAgreement
    ) {
        return new ReactionReportOrder(
                customerEmail,
                projectName,
                targetType,
                oneLineDescription,
                detailDescription,
                pageUrl,
                priceText,
                shippingPolicyText,
                targetCustomer,
                mainQuestion,
                reportPerspective,
                privacyAgreement
        );
    }

    public void setImagePaths(String imagePaths) {
        this.imagePaths = imagePaths;
    }

    public void attachCustomerAccount(Long customerAccountId, String email) {
        this.customerAccountId = customerAccountId;
        if (email != null && !email.isBlank()) {
            this.customerEmail = email.trim().toLowerCase();
        }
    }

    public void markPaid() {
        this.status = OrderStatus.PAID;
    }

    public void markGenerating() {
        this.status = OrderStatus.GENERATING;
    }

    public void markCompleted() {
        this.status = OrderStatus.COMPLETED;
    }

    public void markStopped() {
        this.status = OrderStatus.STOPPED;
    }

    public void markFailed() {
        this.status = OrderStatus.FAILED;
    }

}
