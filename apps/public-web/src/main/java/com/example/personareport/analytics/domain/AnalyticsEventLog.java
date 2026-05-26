package com.example.personareport.analytics.domain;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "analytics_event_log",
        indexes = {
                @Index(name = "idx_analytics_event_created_at", columnList = "created_at"),
                @Index(name = "idx_analytics_event_name_created", columnList = "event_name,created_at"),
                @Index(name = "idx_analytics_event_user_created", columnList = "user_account_id,created_at"),
                @Index(name = "idx_analytics_event_order_created", columnList = "report_order_id,created_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsEventLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String eventName;

    @Column(length = 50)
    private String eventCategory;

    @Column(length = 500)
    private String pagePath;

    @Column(length = 500)
    private String referrer;

    @Column(length = 160)
    private String elementText;

    @Column(length = 80)
    private String anonymousId;

    private Long userAccountId;

    private Long reportOrderId;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @Column(length = 500)
    private String userAgent;

    public static AnalyticsEventLog create(
            String eventName,
            String eventCategory,
            String pagePath,
            String referrer,
            String elementText,
            String anonymousId,
            Long userAccountId,
            Long reportOrderId,
            String metadataJson,
            String userAgent
    ) {
        AnalyticsEventLog log = new AnalyticsEventLog();
        log.eventName = eventName;
        log.eventCategory = eventCategory;
        log.pagePath = pagePath;
        log.referrer = referrer;
        log.elementText = elementText;
        log.anonymousId = anonymousId;
        log.userAccountId = userAccountId;
        log.reportOrderId = reportOrderId;
        log.metadataJson = metadataJson;
        log.userAgent = userAgent;
        return log;
    }
}
