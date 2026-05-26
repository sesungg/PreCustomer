package com.example.personareport.user.domain;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user_account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private boolean termsAccepted;

    @Column(nullable = false)
    private boolean privacyAccepted;

    @Column(nullable = false)
    private boolean marketingAccepted;

    private LocalDateTime termsAcceptedAt;

    private LocalDateTime privacyAcceptedAt;

    private LocalDateTime marketingAcceptedAt;

    public static UserAccount create(String displayName, String email, String passwordHash) {
        return create(displayName, email, passwordHash, true, true, false);
    }

    public static UserAccount create(
            String displayName,
            String email,
            String passwordHash,
            boolean termsAccepted,
            boolean privacyAccepted,
            boolean marketingAccepted
    ) {
        UserAccount account = new UserAccount();
        account.displayName = displayName;
        account.email = normalizeEmail(email);
        account.passwordHash = passwordHash;
        account.role = "USER";
        account.enabled = true;
        account.termsAccepted = termsAccepted;
        account.privacyAccepted = privacyAccepted;
        account.marketingAccepted = marketingAccepted;
        LocalDateTime now = LocalDateTime.now();
        account.termsAcceptedAt = termsAccepted ? now : null;
        account.privacyAcceptedAt = privacyAccepted ? now : null;
        account.marketingAcceptedAt = marketingAccepted ? now : null;
        return account;
    }

    public static String normalizeEmail(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    public boolean hasRequiredConsents() {
        return termsAccepted && privacyAccepted;
    }
}
