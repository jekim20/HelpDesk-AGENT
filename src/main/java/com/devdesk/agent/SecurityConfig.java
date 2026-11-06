package com.devdesk.agent;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/login").permitAll()
                        .requestMatchers("/api/csrf").permitAll()
                        .requestMatchers(
                                "/api/admin/tickets", "/api/ingest", "/api/metrics",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/chat", "/api/history", "/api/me")
                        .hasAnyRole("USER", "ADMIN")
                        .anyRequest().denyAll())
                .formLogin(form -> form
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> response.setStatus(401))
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204))
                        .permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Local accounts are enabled only when their passwords are supplied through the environment. */
    @Bean
    UserDetailsService userDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${app.security.user1-password:}") String user1Password,
            @Value("${app.security.user2-password:}") String user2Password,
            @Value("${app.security.admin-password:}") String adminPassword) {
        List<UserDetails> users = new ArrayList<>();
        addUser(users, passwordEncoder, "user1", user1Password, "USER");
        addUser(users, passwordEncoder, "user2", user2Password, "USER");
        addUser(users, passwordEncoder, "admin", adminPassword, "ADMIN");
        if (users.isEmpty()) {
            log.warn("No local HelpDesk accounts enabled; set APP_USER1_PASSWORD, APP_USER2_PASSWORD, and/or APP_ADMIN_PASSWORD.");
        }
        return new InMemoryUserDetailsManager(users);
    }

    private void addUser(List<UserDetails> users, PasswordEncoder encoder,
                         String username, String rawPassword, String role) {
        if (rawPassword == null || rawPassword.isBlank()) {
            log.warn("Local account '{}' is disabled because its environment password is not set.", username);
            return;
        }
        users.add(User.withUsername(username)
                .password(encoder.encode(rawPassword))
                .roles(role)
                .build());
    }
}
