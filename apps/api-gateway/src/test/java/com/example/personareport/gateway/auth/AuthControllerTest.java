package com.example.personareport.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.gateway.config.GatewaySecurityProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthControllerTest {

    @Test
    void loginAcceptsLocalRawPassword() {
        GatewaySecurityProperties properties = properties("admin");
        AuthController controller = new AuthController(
                properties,
                new BCryptPasswordEncoder(),
                new JwtService(properties)
        );

        var response = controller.login(new AuthController.LoginRequest("admin", "admin")).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().accessToken()).isNotBlank();
    }

    @Test
    void loginAcceptsBcryptPasswordHash() {
        var encoder = new BCryptPasswordEncoder();
        GatewaySecurityProperties properties = properties(encoder.encode("admin"));
        AuthController controller = new AuthController(properties, encoder, new JwtService(properties));

        var response = controller.login(new AuthController.LoginRequest("admin", "admin")).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void loginRejectsWrongPassword() {
        GatewaySecurityProperties properties = properties("admin");
        AuthController controller = new AuthController(
                properties,
                new BCryptPasswordEncoder(),
                new JwtService(properties)
        );

        var response = controller.login(new AuthController.LoginRequest("admin", "wrong")).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void loginPageProvidesBrowserForm() {
        GatewaySecurityProperties properties = properties("admin");
        AuthController controller = new AuthController(
                properties,
                new BCryptPasswordEncoder(),
                new JwtService(properties)
        );

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/auth/login"));
        var response = controller.loginPage(exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("action=\"/auth/login\"");
        assertThat(response.getBody()).contains("Admin Login");
    }

    private GatewaySecurityProperties properties(String password) {
        return new GatewaySecurityProperties(
                new GatewaySecurityProperties.Admin("admin", password),
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
}
