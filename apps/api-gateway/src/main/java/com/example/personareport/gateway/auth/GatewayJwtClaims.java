package com.example.personareport.gateway.auth;

import java.time.Instant;
import java.util.Set;

public record GatewayJwtClaims(String subject, Set<String> roles, Instant expiresAt) {
}
