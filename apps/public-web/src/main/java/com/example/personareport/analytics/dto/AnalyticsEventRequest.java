package com.example.personareport.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record AnalyticsEventRequest(
        @NotBlank @Size(max = 80) String eventName,
        @Size(max = 50) String eventCategory,
        @Size(max = 500) String pagePath,
        @Size(max = 500) String referrer,
        @Size(max = 160) String elementText,
        @Size(max = 80) String anonymousId,
        Long reportOrderId,
        Map<String, Object> metadata
) {
}
