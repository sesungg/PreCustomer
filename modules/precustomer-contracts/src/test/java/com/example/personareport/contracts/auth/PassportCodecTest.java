package com.example.personareport.contracts.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PassportCodecTest {

    private static final String SECRET = "local-passport-secret-change-me-32bytes!";

    @Test
    void verifiesIssuedPassport() {
        String token = PassportCodec.issue(SECRET, "sesungg@gmail.com", List.of("ROLE_ADMIN"), Duration.ofMinutes(5));

        PassportClaims claims = PassportCodec.verify(SECRET, token);

        assertThat(claims.subject()).isEqualTo("sesungg@gmail.com");
        assertThat(claims.roles()).containsExactly("ROLE_ADMIN");
    }

    @Test
    void rejectsTamperedPassport() {
        String token = PassportCodec.issue(SECRET, "admin", List.of("ROLE_ADMIN"), Duration.ofMinutes(5));

        assertThatThrownBy(() -> PassportCodec.verify(SECRET, token + "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
