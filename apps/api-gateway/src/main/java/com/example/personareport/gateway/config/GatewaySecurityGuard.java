package com.example.personareport.gateway.config;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class GatewaySecurityGuard {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityGuard.class);
    private static final String DEFAULT_JWT_SECRET = "local-jwt-secret-change-me-32bytes-minimum";
    private static final String DEFAULT_PASSPORT_SECRET = "local-passport-secret-change-me-32bytes!";

    @Bean
    ApplicationRunner gatewaySecretGuard(GatewaySecurityProperties properties, Environment environment) {
        return args -> {
            boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
            boolean insecure = usesInsecurePassword(properties.admin().password())
                    || DEFAULT_JWT_SECRET.equals(properties.jwt().secret())
                    || DEFAULT_PASSPORT_SECRET.equals(properties.passport().secret());
            if (prod && insecure) {
                throw new IllegalStateException("prod gateway requires ADMIN_PASSWORD, GATEWAY_JWT_SECRET, and GATEWAY_PASSPORT_SECRET.");
            }
            if (!prod && insecure) {
                log.warn("Insecure local gateway credentials are active. Override gateway secrets outside local development.");
            }
        };
    }

    private boolean usesInsecurePassword(String password) {
        return "admin".equals(password)
                || "change-me".equals(password)
                || "change-me-before-prod".equals(password);
    }
}
