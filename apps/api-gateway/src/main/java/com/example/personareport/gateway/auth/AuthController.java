package com.example.personareport.gateway.auth;

import com.example.personareport.gateway.config.GatewaySecurityProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final GatewaySecurityProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(GatewaySecurityProperties properties, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    Mono<ResponseEntity<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        if (!properties.admin().username().equals(request.username())
                || !passwordMatches(request.password(), properties.admin().password())) {
            return Mono.just(ResponseEntity.status(401).build());
        }

        String accessToken = jwtService.issueAdminAccessToken(request.username());
        ResponseCookie cookie = ResponseCookie.from(properties.jwt().cookieName(), accessToken)
                .httpOnly(true)
                .secure(properties.jwt().secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.jwt().accessTokenTtl())
                .build();
        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new LoginResponse("Bearer", accessToken, properties.jwt().accessTokenTtl().toSeconds())));
    }

    @GetMapping("/me")
    Mono<ResponseEntity<MeResponse>> me(ServerWebExchange exchange) {
        String token = exchange.getRequest().getCookies()
                .getOrDefault(properties.jwt().cookieName(), java.util.List.of())
                .stream()
                .findFirst()
                .map(HttpCookie::getValue)
                .orElseGet(() -> jwtService.resolveBearerToken(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)));
        if (token == null) {
            return Mono.just(ResponseEntity.status(401).build());
        }
        return jwtService.parse(token)
                .map(claims -> ResponseEntity.ok(new MeResponse(claims.subject(), claims.expiresAt())))
                .onErrorReturn(ResponseEntity.status(401).build());
    }

    private boolean passwordMatches(String rawPassword, String configuredPassword) {
        if (configuredPassword != null
                && (configuredPassword.startsWith("$2a$")
                || configuredPassword.startsWith("$2b$")
                || configuredPassword.startsWith("$2y$"))) {
            return passwordEncoder.matches(rawPassword, configuredPassword);
        }
        return configuredPassword != null && configuredPassword.equals(rawPassword);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String tokenType, String accessToken, long expiresInSeconds) {
    }

    public record MeResponse(String subject, Instant expiresAt) {
    }
}
