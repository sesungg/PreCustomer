package com.example.personareport.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.admin")
public record AdminSecurityProperties(
        String username,
        String password
) {
    public AdminSecurityProperties {
        username = isBlank(username) ? "admin" : username;
        password = isBlank(password) ? "admin" : password;
    }

    boolean usesInsecureDefaultPassword() {
        return "admin".equals(password)
                || "change-me".equals(password)
                || "change-me-before-prod".equals(password);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
