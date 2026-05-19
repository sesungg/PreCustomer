package com.example.personareport.common.config;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import com.example.personareport.user.domain.UserAccount;
import com.example.personareport.user.repository.UserAccountRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Slf4j
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({AdminSecurityProperties.class, GatewayPassportProperties.class})
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            GatewayPassportAuthenticationFilter gatewayPassportAuthenticationFilter
    ) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        .requestMatchers("/", "/orders/**", "/login", "/signup", "/error", "/favicon.ico").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/account/**").hasRole("USER")
                        .requestMatchers("/admin/**", "/api/shopping/naver/**", "/uploads/**").hasRole("ADMIN")
                        .anyRequest().denyAll()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler((request, response, authentication) -> {
                            boolean admin = authentication.getAuthorities().stream()
                                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
                            response.sendRedirect(admin ? "/admin/orders" : "/account/reports");
                        })
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutRequestMatcher(new OrRequestMatcher(
                                new AntPathRequestMatcher("/logout", "POST"),
                                new AntPathRequestMatcher("/admin/logout", "POST")
                        ))
                        .logoutSuccessUrl("/")
                        .permitAll()
                )
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**"))
                .addFilterBefore(gatewayPassportAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(AdminSecurityProperties properties,
                                          PasswordEncoder passwordEncoder,
                                          UserAccountRepository userAccountRepository) {
        var admin = User.withUsername(properties.username())
                .password(passwordEncoder.encode(properties.password()))
                .roles("ADMIN")
                .build();
        return username -> {
            if (properties.username().equals(username)) {
                return admin;
            }
            return userAccountRepository.findByEmail(UserAccount.normalizeEmail(username))
                    .map(account -> User.withUsername(account.getEmail())
                            .password(account.getPasswordHash())
                            .roles(account.getRole())
                            .disabled(!account.isEnabled())
                            .build())
                    .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        };
    }

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    ApplicationRunner adminPasswordGuard(AdminSecurityProperties properties, Environment environment) {
        return args -> {
            boolean adminEnabled = environment.getProperty("app.web.admin-enabled", Boolean.class, true);
            boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
            if (adminEnabled && prod && properties.usesInsecureDefaultPassword()) {
                throw new IllegalStateException("prod admin-web requires ADMIN_USERNAME and ADMIN_PASSWORD.");
            }
            if (adminEnabled && properties.usesInsecureDefaultPassword()) {
                log.warn("Insecure local admin password is active. Set ADMIN_USERNAME and ADMIN_PASSWORD outside local development.");
            }
        };
    }
}
