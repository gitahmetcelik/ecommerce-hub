package com.ecommercehub.domain.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hub.auth")
public class AuthProperties {

    /**
     * HMAC key for access tokens. The dev default exists so the application starts
     * without configuration. <b>Production must override it</b> — anyone holding this
     * value can mint a token for any user in any organization, so it is exactly as
     * sensitive as the database password.
     */
    private String jwtSecret = "dev-only-hub-jwt-secret-change-me-in-production-0123456789";

    /**
     * Deliberately short. An access token cannot be revoked — it is valid until it
     * expires, by construction — so its lifetime is the true window in which a
     * disabled user or a stolen token still works. Revocation lives on the refresh
     * token, which is why that one has a database row and this one does not.
     */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    private Duration refreshTokenTtl = Duration.ofDays(14);
    private Duration invitationTtl = Duration.ofDays(7);
    private Duration passwordResetTtl = Duration.ofHours(2);

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public Duration getInvitationTtl() {
        return invitationTtl;
    }

    public void setInvitationTtl(Duration invitationTtl) {
        this.invitationTtl = invitationTtl;
    }

    public Duration getPasswordResetTtl() {
        return passwordResetTtl;
    }

    public void setPasswordResetTtl(Duration passwordResetTtl) {
        this.passwordResetTtl = passwordResetTtl;
    }
}
