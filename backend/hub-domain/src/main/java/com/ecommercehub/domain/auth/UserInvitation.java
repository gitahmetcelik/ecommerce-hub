package com.ecommercehub.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Plan §10: an organization administrator adds a user, and the user sets their own
 * password. The administrator never chooses a password on someone else's behalf —
 * a password the admin knows is not a credential that identifies the user.
 */
@Entity
@Table(name = "user_invitation", schema = "hub")
public class UserInvitation {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, updatable = false)
    private HubRole roleName;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "invited_by_user_id")
    private UUID invitedByUserId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected UserInvitation() {
        // JPA
    }

    public UserInvitation(UUID id, UUID organizationId, UUID userId, String email, HubRole roleName,
                           String tokenHash, UUID invitedByUserId, Instant expiresAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.userId = userId;
        this.email = email;
        this.roleName = roleName;
        this.tokenHash = tokenHash;
        this.invitedByUserId = invitedByUserId;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public HubRole getRoleName() {
        return roleName;
    }

    public boolean isUsable(Instant now) {
        return acceptedAt == null && now.isBefore(expiresAt);
    }

    public void accept(Instant at) {
        this.acceptedAt = at;
    }
}
