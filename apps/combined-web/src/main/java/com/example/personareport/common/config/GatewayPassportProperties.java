package com.example.personareport.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.gateway.passport")
public record GatewayPassportProperties(
        @DefaultValue("true") boolean enabled,
        String secret,
        String headerName
) {

    public GatewayPassportProperties {
        secret = isBlank(secret) ? "local-passport-secret-change-me-32bytes!" : secret;
        headerName = isBlank(headerName) ? "X-PreCustomer-Passport" : headerName;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
