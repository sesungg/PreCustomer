package com.example.personareport.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.gateway.auth.JwtService;
import com.example.personareport.gateway.config.GatewaySecurityProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void issuesAndParsesAdminToken() {
        var properties = new GatewaySecurityProperties(
                new GatewaySecurityProperties.Admin("admin", "hash"),
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
        JwtService service = new JwtService(properties);

        String token = service.issueAdminAccessToken("admin");

        assertThat(service.parse(token).block().roles()).containsExactly("ROLE_ADMIN");
    }
}
