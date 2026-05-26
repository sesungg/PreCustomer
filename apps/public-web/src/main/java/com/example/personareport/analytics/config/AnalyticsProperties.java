package com.example.personareport.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.analytics")
public record AnalyticsProperties(
        @DefaultValue Ga4 ga4,
        @DefaultValue EventLog eventLog
) {

    public boolean ga4Renderable() {
        return ga4.enabled() && ga4.measurementId() != null && !ga4.measurementId().isBlank();
    }

    public record Ga4(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String measurementId
    ) {
    }

    public record EventLog(
            @DefaultValue("true") boolean enabled
    ) {
    }
}
