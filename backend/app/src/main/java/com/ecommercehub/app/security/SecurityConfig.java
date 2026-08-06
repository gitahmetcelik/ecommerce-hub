package com.ecommercehub.app.security;

import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * plan §10. Stateless: there is no server-side session, the access token is the whole
 * credential, and revocation is handled by the refresh token's database row.
 *
 * <p>Two things here are deliberately <em>not</em> authenticated with a bearer token.
 * The webhook endpoints authenticate with the channel's HMAC signature over the raw
 * body (Faz 2) — a channel has no user account to log in as. And the auth endpoints
 * themselves obviously cannot require a token to obtain one.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * BCrypt rather than Argon2id (plan §10 allows either). Argon2 in Spring Security
     * needs BouncyCastle on the classpath; BCrypt is already there, is not the weak
     * link in this system, and can be swapped later by changing this one bean —
     * stored hashes carry their own algorithm prefix, so a migration re-encodes on
     * next login rather than invalidating every password.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter)
            throws Exception {
        http
            // No cookies are used, so there is no ambient authority for a cross-site
            // request to ride on and nothing for a CSRF token to protect.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/auth/login", "/auth/refresh", "/auth/logout",
                            "/auth/invitations/accept", "/auth/password-reset/**").permitAll()
                    // HMAC-authenticated (plan §3): the signature is verified over the
                    // raw body inside the ingest path, not here.
                    .requestMatchers("/webhooks/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                    // Coarse gate only. The real decisions are per-action and live in the
                    // domain services (approve needs OPERATOR, refund needs ADMIN), where
                    // every caller hits them, not just this one.
                    .requestMatchers("/internal/**").hasAnyAuthority(
                            HubRole.OBSERVER.authority(), HubRole.OPERATOR.authority(), HubRole.ADMIN.authority())
                    .requestMatchers("/auth/**").authenticated()
                    .anyRequest().authenticated())
            .exceptionHandling(handling -> handling
                    .authenticationEntryPoint((request, response, ex) ->
                            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Authentication required"))
                    .accessDeniedHandler((request, response, ex) ->
                            response.sendError(HttpStatus.FORBIDDEN.value(), "Insufficient permissions")))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
