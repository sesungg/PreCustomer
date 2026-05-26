package com.example.personareport.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.gateway.config.GatewaySecurityProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.core.publisher.Mono;

class JwtPassportGlobalFilterTest {

    @Test
    void protectedRouteWithoutJwt_returnsUnauthorizedInsteadOfFailingOnReadOnlyHeaders() {
        JwtPassportGlobalFilter filter = filter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/orders")
                        .header("X-PreCustomer-Passport", "spoofed")
                        .header("X-Authenticated-User", "spoofed")
        );
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.filter(exchange, chainExchange -> {
            chainInvoked.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainInvoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void publicRoute_removesSpoofedInternalHeadersBeforeProxying() {
        JwtPassportGlobalFilter filter = filter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/login")
                        .header("X-PreCustomer-Passport", "spoofed")
                        .header("X-Authenticated-User", "spoofed")
                        .header("X-Authenticated-Roles", "ADMIN")
        );

        filter.filter(exchange, chainExchange -> {
            assertThat(chainExchange.getRequest().getHeaders()).doesNotContainKey("X-PreCustomer-Passport");
            assertThat(chainExchange.getRequest().getHeaders()).doesNotContainKey("X-Authenticated-User");
            assertThat(chainExchange.getRequest().getHeaders()).doesNotContainKey("X-Authenticated-Roles");
            return Mono.empty();
        }).block();
    }

    private JwtPassportGlobalFilter filter() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties(
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
        return new JwtPassportGlobalFilter(properties, new JwtService(properties));
    }
}
