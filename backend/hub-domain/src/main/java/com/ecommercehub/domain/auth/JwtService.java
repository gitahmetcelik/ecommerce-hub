package com.ecommercehub.domain.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints and verifies access tokens (Plan §10).
 *
 * <p>The organization id is a <em>claim</em>, and everything downstream reads the
 * tenant from here rather than from the request. That is the whole point: before this
 * phase the internal endpoints took organizationId as a query parameter, which — once
 * there are authenticated users at all — means any user can read any tenant's data by
 * changing a number in the URL. A signed claim cannot be changed by the caller.
 */
@Component
@EnableConfigurationProperties(AuthProperties.class)
public class JwtService {

    private static final String CLAIM_ORGANIZATION = "org";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_EMAIL = "email";

    private final SecretKey key;
    private final AuthProperties properties;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(AppUser user, List<HubRole> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_ORGANIZATION, user.getOrganizationId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLES, roles.stream().map(Enum::name).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getAccessTokenTtl())))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * @throws InvalidTokenException on anything wrong with the token — bad signature,
     *         expiry, malformed structure. Collapsed into one exception on purpose: the
     *         caller gets 401 either way, and distinguishing them in a response tells an
     *         attacker which part of a forged token to fix next.
     */
    public AuthenticatedUser parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

            List<?> rawRoles = claims.get(CLAIM_ROLES, List.class);
            List<HubRole> roles = rawRoles == null
                    ? List.of()
                    : rawRoles.stream().map(String::valueOf).map(HubRole::valueOf).toList();

            return new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(CLAIM_ORGANIZATION, String.class)),
                    claims.get(CLAIM_EMAIL, String.class),
                    roles);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Access token is not valid", e);
        }
    }
}
