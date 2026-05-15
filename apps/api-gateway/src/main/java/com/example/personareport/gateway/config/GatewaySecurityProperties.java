package com.example.personareport.gateway.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.gateway.security")
public record GatewaySecurityProperties(
        Admin admin,
        Jwt jwt,
        Passport passport
) {

    public record Admin(
            @NotBlank String username,
            @NotBlank String password
    ) {
        public Admin(
                @DefaultValue("admin") String username,
                @DefaultValue("admin") String password
        ) {
            this.username = username;
            this.password = password;
        }
    }

    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotBlank String cookieName,
            Duration accessTokenTtl,
            boolean secureCookie
    ) {
        public Jwt(
                @DefaultValue("local-jwt-secret-change-me-32bytes-minimum") String secret,
                @DefaultValue("precustomer-api-gateway") String issuer,
                @DefaultValue("PCR_ACCESS_TOKEN") String cookieName,
                @DefaultValue("PT2H") Duration accessTokenTtl,
                @DefaultValue("false") boolean secureCookie
        ) {
            this.secret = secret;
            this.issuer = issuer;
            this.cookieName = cookieName;
            this.accessTokenTtl = accessTokenTtl;
            this.secureCookie = secureCookie;
        }
    }

    public record Passport(
            @NotBlank String secret,
            @NotBlank String headerName,
            Duration ttl
    ) {
        public Passport(
                @DefaultValue("local-passport-secret-change-me-32bytes!") String secret,
                @DefaultValue("X-PreCustomer-Passport") String headerName,
                @DefaultValue("PT5M") Duration ttl
        ) {
            this.secret = secret;
            this.headerName = headerName;
            this.ttl = ttl;
        }
    }
}
