package com.example.personareport.gateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GatewaySecurityGuardTest {

    private final GatewaySecurityGuard guard = new GatewaySecurityGuard();

    @Test
    void rejectsDefaultSecretsInProd() {
        var environment = prodEnvironment();

        assertThatThrownBy(() -> guard.gatewaySecretGuard(localProperties(), environment).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod gateway requires");
    }

    @Test
    void allowsStrongSecretsInProd() {
        var environment = prodEnvironment();

        assertThatCode(() -> guard.gatewaySecretGuard(strongProperties(), environment).run(null))
                .doesNotThrowAnyException();
    }

    private MockEnvironment prodEnvironment() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private GatewaySecurityProperties localProperties() {
        return new GatewaySecurityProperties(
                new GatewaySecurityProperties.Admin("admin", "admin"),
                new GatewaySecurityProperties.Jwt(
                        "local-jwt-secret-change-me-32bytes-minimum",
                        "test-issuer",
                        "PCR_ACCESS_TOKEN",
                        Duration.ofMinutes(30),
                        false
                ),
                new GatewaySecurityProperties.Passport(
                        "local-passport-secret-change-me-32bytes!",
                        "X-PreCustomer-Passport",
                        Duration.ofMinutes(5)
                )
        );
    }

    private GatewaySecurityProperties strongProperties() {
        return new GatewaySecurityProperties(
                new GatewaySecurityProperties.Admin("admin", "not-a-default-password"),
                new GatewaySecurityProperties.Jwt(
                        "strong-jwt-secret-change-me-32bytes-plus",
                        "test-issuer",
                        "PCR_ACCESS_TOKEN",
                        Duration.ofMinutes(30),
                        true
                ),
                new GatewaySecurityProperties.Passport(
                        "strong-passport-secret-change-me-32bytes",
                        "X-PreCustomer-Passport",
                        Duration.ofMinutes(5)
                )
        );
    }
}
