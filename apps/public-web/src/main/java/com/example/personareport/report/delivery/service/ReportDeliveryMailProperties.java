package com.example.personareport.report.delivery.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.delivery.mail")
public record ReportDeliveryMailProperties(
        boolean enabled,
        String from,
        String baseUrl
) {
    public ReportDeliveryMailProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("no-reply@localhost") String from,
            @DefaultValue("http://localhost:8080") String baseUrl
    ) {
        this.enabled = enabled;
        this.from = from;
        this.baseUrl = baseUrl;
    }
}
