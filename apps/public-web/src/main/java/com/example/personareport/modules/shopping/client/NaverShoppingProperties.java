package com.example.personareport.modules.shopping.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "naver.shopping")
public record NaverShoppingProperties(
        String clientId,
        String clientSecret,
        String baseUrl,
        int displaySize,
        int timeoutMillis,
        String scoringVersion
) {
    public NaverShoppingProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://openapi.naver.com/v1/search/shop.json";
        if (displaySize <= 0) displaySize = 100;
        if (timeoutMillis <= 0) timeoutMillis = 5000;
        if (scoringVersion == null || scoringVersion.isBlank()) scoringVersion = "NAVER_SHOPPING_CANDIDATE_V1";
    }
}
