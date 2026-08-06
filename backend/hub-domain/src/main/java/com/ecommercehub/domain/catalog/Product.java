package com.ecommercehub.domain.catalog;

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
 * Minimal shape for Phase 2 — full catalog matching (channel_product_mapping,
 * mapping_candidate) is Phase 3. Order processing in this phase creates a variant
 * directly from a channel SKU when no mapping exists yet.
 */
@Entity
@Table(name = "product", schema = "hub")
public class Product {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String title;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Product() {
        // JPA
    }

    public Product(UUID id, UUID organizationId, String title) {
        this.id = id;
        this.organizationId = organizationId;
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getTitle() {
        return title;
    }
}
