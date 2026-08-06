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

/** One granted role. A user may hold several; the effective authority is the highest. */
@Entity
@Table(name = "user_role", schema = "hub")
public class UserRole {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false)
    private HubRole roleName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected UserRole() {
        // JPA
    }

    public UserRole(UUID id, UUID organizationId, UUID userId, HubRole roleName) {
        this.id = id;
        this.organizationId = organizationId;
        this.userId = userId;
        this.roleName = roleName;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public HubRole getRoleName() {
        return roleName;
    }
}
