package com.example.personareport.contracts.auth;

import java.time.Instant;
import java.util.Set;

public record PassportClaims(
        String subject,
        Set<String> roles,
        Instant issuedAt,
        Instant expiresAt
) {
}
