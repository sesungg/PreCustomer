package com.example.personareport.gateway.auth;

import com.example.personareport.contracts.auth.PassportCodec;
import com.example.personareport.gateway.config.GatewaySecurityProperties;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtPassportGlobalFilter implements GlobalFilter, Ordered {

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/orders",
            "/account",
            "/events",
            "/login",
            "/signup",
            "/logout",
            "/auth",
            "/actuator",
            "/css",
            "/js",
            "/img",
            "/webjars"
    );

    private static final List<String> PROTECTED_PREFIXES = List.of(
            "/admin",
            "/api/shopping/naver",
            "/uploads"
    );

    private static final List<String> INTERNAL_HEADERS = List.of(
            "X-Authenticated-User",
            "X-Authenticated-Roles"
    );

    private final GatewaySecurityProperties properties;
    private final JwtService jwtService;

    public JwtPassportGlobalFilter(GatewaySecurityProperties properties, JwtService jwtService) {
        this.properties = properties;
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        ServerWebExchange sanitizedExchange = exchange.mutate()
                .request(requestWithHeaders(exchange.getRequest(), sanitizedHeaders(exchange.getRequest().getHeaders())))
                .build();

        if (isPublic(path)) {
            return chain.filter(sanitizedExchange);
        }
        if (!isProtected(path)) {
            return chain.filter(sanitizedExchange);
        }

        String token = resolveToken(exchange);
        if (token == null) {
            return unauthorized(exchange);
        }

        return jwtService.parse(token)
                .flatMap(claims -> {
                    String passport = PassportCodec.issue(
                            properties.passport().secret(),
                            claims.subject(),
                            claims.roles(),
                            properties.passport().ttl()
                    );
                    HttpHeaders headers = sanitizedHeaders(exchange.getRequest().getHeaders());
                    headers.set(properties.passport().headerName(), passport);
                    headers.set("X-Authenticated-User", claims.subject());
                    headers.set("X-Authenticated-Roles", String.join(",", claims.roles()));
                    ServerHttpRequest authenticatedRequest = requestWithHeaders(exchange.getRequest(), headers);
                    return chain.filter(sanitizedExchange.mutate().request(authenticatedRequest).build());
                })
                .onErrorResume(error -> unauthorized(exchange));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private String resolveToken(ServerWebExchange exchange) {
        String bearer = jwtService.resolveBearerToken(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (bearer != null) {
            return bearer;
        }
        return exchange.getRequest().getCookies()
                .getOrDefault(properties.jwt().cookieName(), List.of())
                .stream()
                .findFirst()
                .map(HttpCookie::getValue)
                .orElse(null);
    }

    private boolean isPublic(String path) {
        return "/".equals(path) || "/favicon.ico".equals(path) || "/error".equals(path)
                || PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private boolean isProtected(String path) {
        return PROTECTED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private HttpHeaders sanitizedHeaders(HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(source);
        headers.remove(properties.passport().headerName());
        INTERNAL_HEADERS.forEach(headers::remove);
        return headers;
    }

    private ServerHttpRequest requestWithHeaders(ServerHttpRequest request, HttpHeaders headers) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
