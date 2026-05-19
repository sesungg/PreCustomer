package com.example.personareport.user.domain;

import com.example.personareport.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    public static UserAccount create(String displayName, String email, String passwordHash) {
        UserAccount account = new UserAccount();
        account.displayName = displayName;
        account.email = normalizeEmail(email);
        account.passwordHash = passwordHash;
        account.role = "USER";
        account.enabled = true;
        return account;
    }

    public static String normalizeEmail(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
