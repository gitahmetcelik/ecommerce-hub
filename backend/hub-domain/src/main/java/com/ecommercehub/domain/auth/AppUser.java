package com.ecommercehub.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Plan §10. A user belongs to exactly one organization — the same human working for
 * two organizations is two accounts, which is why the email uniqueness constraint is
 * (organization_id, email) and not email alone.
 */
@Entity
@Table(name = "app_user", schema = "hub")
public class AppUser {

    /** Cannot authenticate yet: created by an invitation, waiting for it to be accepted. */
    public static final String STATUS_INVITED = "INVITED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected AppUser() {
        // JPA
    }

    public AppUser(UUID id, UUID organizationId, String email, String passwordHash, String fullName, String status) {
        this.id = id;
        this.organizationId = organizationId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getStatus() {
        return status;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public boolean canAuthenticate() {
        return STATUS_ACTIVE.equals(status);
    }

    public void activateWithPassword(String passwordHash, String fullName) {
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.status = STATUS_ACTIVE;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public void disable() {
        this.status = STATUS_DISABLED;
    }
}
