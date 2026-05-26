package com.example.personareport.analytics.web;

import com.example.personareport.analytics.dto.AnalyticsEventRequest;
import com.example.personareport.analytics.service.AnalyticsEventLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/events")
@ConditionalOnProperty(prefix = "app.web", name = "public-enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsEventController {

    private final AnalyticsEventLogService analyticsEventLogService;

    @PostMapping("/log")
    public ResponseEntity<Void> log(
            @Valid @RequestBody AnalyticsEventRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        analyticsEventLogService.recordClientEvent(request, authentication, servletRequest);
        return ResponseEntity.noContent().build();
    }
}
