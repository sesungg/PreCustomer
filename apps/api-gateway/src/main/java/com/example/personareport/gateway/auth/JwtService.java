package com.example.personareport.gateway.auth;

import com.example.personareport.gateway.config.GatewaySecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class JwtService {

    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewaySecurityProperties properties;
    private final SecretKey signingKey;

    public JwtService(GatewaySecurityProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAdminAccessToken(String subject) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().accessTokenTtl());
        return Jwts.builder()
                .setIssuer(properties.jwt().issuer())
                .setSubject(subject)
                .claim("roles", List.of("ROLE_ADMIN"))
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiresAt))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public Mono<GatewayJwtClaims> parse(String token) {
        return Mono.fromCallable(() -> {
            Claims claims = Jwts.parserBuilder()
                    .requireIssuer(properties.jwt().issuer())
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            Object rolesClaim = claims.get("roles");
            Set<String> roles = new LinkedHashSet<>();
            if (rolesClaim instanceof List<?> roleList) {
                roleList.forEach(role -> roles.add(String.valueOf(role)));
            }
            if (roles.isEmpty()) {
                throw new IllegalArgumentException("JWT has no roles");
            }
            return new GatewayJwtClaims(claims.getSubject(), roles, claims.getExpiration().toInstant());
        });
    }

    public String resolveBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorizationHeader.substring(BEARER_PREFIX.length());
    }
}
