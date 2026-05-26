package com.example.personareport.gateway.auth;

import com.example.personareport.gateway.config.GatewaySecurityProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.MultiValueMap;
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

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    Mono<ResponseEntity<String>> loginPage(ServerWebExchange exchange) {
        boolean error = exchange.getRequest().getQueryParams().containsKey("error");
        String errorBlock = error
                ? "<p style=\"color:#b91c1c;margin:0 0 14px\">Invalid username or password.</p>"
                : "";
        String html = """
                <!doctype html>
                <html lang="ko">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Admin Login</title>
                </head>
                <body style="font-family:system-ui,-apple-system,BlinkMacSystemFont,sans-serif;margin:0;background:#f8fafc;color:#111827">
                    <main style="max-width:360px;margin:12vh auto;padding:28px;background:#fff;border:1px solid #e5e7eb;border-radius:8px">
                        <h1 style="font-size:24px;margin:0 0 18px">Admin Login</h1>
                        %s
                        <form method="post" action="/auth/login">
                            <label style="display:block;margin-bottom:10px">Username
                                <input name="username" autocomplete="username" required style="box-sizing:border-box;width:100%%;margin-top:6px;padding:10px;border:1px solid #d1d5db;border-radius:6px">
                            </label>
                            <label style="display:block;margin-bottom:18px">Password
                                <input name="password" type="password" autocomplete="current-password" required style="box-sizing:border-box;width:100%%;margin-top:6px;padding:10px;border:1px solid #d1d5db;border-radius:6px">
                            </label>
                            <button type="submit" style="width:100%%;padding:11px;border:0;border-radius:6px;background:#111827;color:#fff;font-weight:700">Login</button>
                        </form>
                    </main>
                </body>
                </html>
                """.formatted(errorBlock);
        return Mono.just(ResponseEntity.ok(html));
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        if (!properties.admin().username().equals(request.username())
                || !passwordMatches(request.password(), properties.admin().password())) {
            return Mono.just(ResponseEntity.status(401).build());
        }

        String accessToken = issueAccessToken(request.username());
        ResponseCookie cookie = accessTokenCookie(accessToken);
        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new LoginResponse("Bearer", accessToken, properties.jwt().accessTokenTtl().toSeconds())));
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    Mono<ResponseEntity<Void>> loginForm(ServerWebExchange exchange) {
        return exchange.getFormData()
                .map(form -> {
                    String username = first(form, "username");
                    String password = first(form, "password");
                    if (!properties.admin().username().equals(username)
                            || !passwordMatches(password, properties.admin().password())) {
                        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                                .header(HttpHeaders.LOCATION, "/auth/login?error=1")
                                .build();
                    }
                    String accessToken = issueAccessToken(username);
                    return ResponseEntity.status(HttpStatus.SEE_OTHER)
                            .header(HttpHeaders.SET_COOKIE, accessTokenCookie(accessToken).toString())
                            .header(HttpHeaders.LOCATION, "/admin/orders")
                            .build();
                });
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

    private String issueAccessToken(String username) {
        return jwtService.issueAdminAccessToken(username);
    }

    private ResponseCookie accessTokenCookie(String accessToken) {
        return ResponseCookie.from(properties.jwt().cookieName(), accessToken)
                .httpOnly(true)
                .secure(properties.jwt().secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.jwt().accessTokenTtl())
                .build();
    }

    private String first(MultiValueMap<String, String> form, String name) {
        String value = form.getFirst(name);
        return value == null ? "" : value;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String tokenType, String accessToken, long expiresInSeconds) {
    }

    public record MeResponse(String subject, Instant expiresAt) {
    }
}
