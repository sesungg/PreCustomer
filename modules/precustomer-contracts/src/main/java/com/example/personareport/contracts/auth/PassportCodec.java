package com.example.personareport.contracts.auth;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PassportCodec {

    private static final String VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private PassportCodec() {
    }

    public static String issue(String secret, String subject, Collection<String> roles, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Instant now = Instant.now();
        return issue(secret, subject, roles, now, now.plus(ttl));
    }

    public static String issue(String secret, String subject, Collection<String> roles, Instant issuedAt, Instant expiresAt) {
        validateSecret(secret);
        validateSubject(subject);
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
        if (expiresAt == null || issuedAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }

        String roleList = String.join(",", roles.stream()
                .map(role -> {
                    validateRole(role);
                    return role;
                })
                .toList());
        String body = String.join(".",
                VERSION,
                encode(subject),
                encode(roleList),
                Long.toString(issuedAt.getEpochSecond()),
                Long.toString(expiresAt.getEpochSecond())
        );
        return body + "." + encode(hmac(secret, body));
    }

    public static PassportClaims verify(String secret, String token) {
        validateSecret(secret);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("passport token is blank");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("invalid passport token format");
        }

        String body = String.join(".", Arrays.copyOf(parts, 5));
        byte[] expectedSignature = hmac(secret, body);
        byte[] actualSignature = decodeBytes(parts[5]);
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new IllegalArgumentException("invalid passport signature");
        }

        String subject = decode(parts[1]);
        Set<String> roles = new LinkedHashSet<>(Arrays.asList(decode(parts[2]).split(",")));
        Instant issuedAt = Instant.ofEpochSecond(Long.parseLong(parts[3]));
        Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[4]));
        if (!expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("passport token expired");
        }
        return new PassportClaims(subject, roles, issuedAt, expiresAt);
    }

    private static void validateSecret(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("passport secret must be at least 32 characters");
        }
    }

    private static void validateSubject(String value) {
        if (value == null || value.isBlank() || containsLineBreak(value)) {
            throw new IllegalArgumentException("subject contains an invalid character");
        }
    }

    private static void validateRole(String value) {
        if (value == null || value.isBlank() || value.contains(",") || containsLineBreak(value)) {
            throw new IllegalArgumentException("role contains an invalid character");
        }
    }

    private static boolean containsLineBreak(String value) {
        return value.contains("\n") || value.contains("\r");
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] value) {
        return ENCODER.encodeToString(value);
    }

    private static String decode(String value) {
        return new String(decodeBytes(value), StandardCharsets.UTF_8);
    }

    private static byte[] decodeBytes(String value) {
        return DECODER.decode(value);
    }

    private static byte[] hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Unable to sign passport", e);
        }
    }
}
