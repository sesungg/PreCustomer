package com.example.personareport.analytics.service;

import com.example.personareport.analytics.config.AnalyticsProperties;
import com.example.personareport.analytics.domain.AnalyticsEventLog;
import com.example.personareport.analytics.dto.AnalyticsEventRequest;
import com.example.personareport.analytics.repository.AnalyticsEventLogRepository;
import com.example.personareport.user.domain.UserAccount;
import com.example.personareport.user.repository.UserAccountRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsEventLogService {

    private static final int METADATA_LIMIT = 4000;

    private final AnalyticsEventLogRepository repository;
    private final UserAccountRepository userAccountRepository;
    private final ObjectMapper objectMapper;
    private final AnalyticsProperties properties;

    public void recordClientEvent(AnalyticsEventRequest event, Authentication authentication, HttpServletRequest request) {
        if (!properties.eventLog().enabled()) return;
        record(
                event.eventName(),
                event.eventCategory(),
                event.pagePath(),
                event.referrer(),
                event.elementText(),
                event.anonymousId(),
                resolveUserAccountId(authentication),
                event.reportOrderId(),
                toJson(event.metadata()),
                userAgent(request)
        );
    }

    public void recordServerEvent(
            String eventName,
            String eventCategory,
            Long reportOrderId,
            Long userAccountId,
            Map<String, Object> metadata,
            HttpServletRequest request
    ) {
        if (!properties.eventLog().enabled()) return;
        record(
                eventName,
                eventCategory,
                path(request),
                referrer(request),
                null,
                null,
                userAccountId,
                reportOrderId,
                toJson(metadata),
                userAgent(request)
        );
    }

    private void record(
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
        try {
            repository.saveAndFlush(AnalyticsEventLog.create(
                    clean(eventName, 80),
                    clean(eventCategory, 50),
                    clean(pagePath, 500),
                    clean(referrer, 500),
                    clean(elementText, 160),
                    clean(anonymousId, 80),
                    userAccountId,
                    reportOrderId,
                    clean(metadataJson, METADATA_LIMIT),
                    clean(userAgent, 500)
            ));
        } catch (Exception exception) {
            log.warn("analytics event log skipped. eventName={}, error={}", eventName, exception.getMessage());
        }
    }

    private Long resolveUserAccountId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        return userAccountRepository.findByEmail(UserAccount.normalizeEmail(authentication.getName()))
                .map(UserAccount::getId)
                .orElse(null);
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            return "{\"serialization\":\"failed\"}";
        }
    }

    private String path(HttpServletRequest request) {
        if (request == null) return null;
        String query = request.getQueryString();
        return query == null || query.isBlank() ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private String referrer(HttpServletRequest request) {
        return request == null ? null : request.getHeader("Referer");
    }

    private String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }

    private String clean(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
