package com.example.personareport.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminSecurityPropertiesTest {

    @Test
    void detectsInsecureDefaultPasswords() {
        assertThat(new AdminSecurityProperties("admin", "admin").usesInsecureDefaultPassword()).isTrue();
        assertThat(new AdminSecurityProperties("admin", "change-me").usesInsecureDefaultPassword()).isTrue();
        assertThat(new AdminSecurityProperties("admin", "change-me-before-prod").usesInsecureDefaultPassword()).isTrue();
        assertThat(new AdminSecurityProperties("admin", "local-secret").usesInsecureDefaultPassword()).isFalse();
    }
}
