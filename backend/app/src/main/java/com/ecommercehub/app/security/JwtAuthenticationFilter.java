package com.ecommercehub.app.security;

import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InvalidTokenException;
import com.ecommercehub.domain.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns a bearer token into an authenticated principal.
 *
 * <p>The principal is the {@link AuthenticatedUser} record itself, organization id
 * included, so downstream code reads the tenant from the verified token instead of
 * from a request parameter. That substitution is the security-relevant part of this
 * phase: with users but without it, any authenticated user could read any tenant by
 * editing an id in the URL.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // No credentials presented. Not an error here — the filter chain decides
            // whether this particular endpoint needed them.
            chain.doFilter(request, response);
            return;
        }

        try {
            AuthenticatedUser user = jwtService.parseAccessToken(header.substring(BEARER_PREFIX.length()));

            var authorities = user.roles().stream()
                    .map(HubRole::authority)
                    .map(SimpleGrantedAuthority::new)
                    .toList();

            var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (InvalidTokenException e) {
            // Leave the context empty and let the chain reject it. Writing a 401 here
            // would also reject requests to endpoints that are public, merely because
            // the caller happened to send a stale token along with them.
            log.debug("Rejected access token on {}: {}", request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }
}
