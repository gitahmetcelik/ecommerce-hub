package com.ecommercehub.app.security;

import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.JwtService;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

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

    /**
     * plan Faz 6: the dashboard runs on its own origin, so the browser preflights every
     * call. The allowed origins are configured, never wildcarded — {@code *} would let
     * any page on the internet call this API with a user's token if it could get one.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${hub.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Credentials stay off: the token travels in the Authorization header, not a
        // cookie, so nothing needs to ride along automatically.
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
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
