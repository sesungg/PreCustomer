package com.example.personareport.report.delivery.domain;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "report_delivery_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportDeliveryRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long reportOrderId;

    private Long userAccountId;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 30)
    private String status;

    private LocalDateTime sentAt;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    public static ReportDeliveryRequest createEmailOnly(Long reportOrderId, String email) {
        ReportDeliveryRequest request = new ReportDeliveryRequest();
        request.reportOrderId = reportOrderId;
        request.email = normalizeEmail(email);
        request.status = ReportDeliveryStatus.PENDING;
        return request;
    }

    public void updateEmailOnly(String email) {
        this.userAccountId = null;
        this.email = normalizeEmail(email);
        this.status = ReportDeliveryStatus.PENDING;
        this.sentAt = null;
        this.lastError = null;
    }

    public void updateAccount(Long userAccountId, String email) {
        this.userAccountId = userAccountId;
        this.email = normalizeEmail(email);
        this.status = ReportDeliveryStatus.PENDING;
        this.sentAt = null;
        this.lastError = null;
    }

    public void markReady(String message) {
        this.status = ReportDeliveryStatus.READY;
        this.lastError = message;
    }

    public void markSent() {
        this.status = ReportDeliveryStatus.SENT;
        this.sentAt = LocalDateTime.now();
        this.lastError = null;
    }

    public void markFailed(String message) {
        this.status = ReportDeliveryStatus.FAILED;
        this.lastError = message;
    }

    private static String normalizeEmail(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
