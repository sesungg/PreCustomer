package com.example.personareport.analytics.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "app.web", name = "public-enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsModelAdvice {

    private final AnalyticsProperties properties;

    @ModelAttribute
    public void addAnalyticsAttributes(Model model) {
        model.addAttribute("ga4Enabled", properties.ga4Renderable());
        model.addAttribute("ga4MeasurementId", properties.ga4().measurementId());
        model.addAttribute("eventLogEnabled", properties.eventLog().enabled());
    }
}
