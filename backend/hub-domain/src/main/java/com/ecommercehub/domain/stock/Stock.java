package com.ecommercehub.domain.stock;

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
 * plan §3: satilabilir(kanal) = fiziksel - rezerve - tampon(kanal). The buffer term
 * (stock_buffer, per-channel) and oversell detection are Faz 4 — this entity only
 * carries the two counters Faz 2's reservation lifecycle needs. @Version enforces
 * that concurrent adjustments (two order items reserving at once) never silently
 * overwrite each other.
 */
@Entity
@Table(name = "stock", schema = "hub")
public class Stock {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Column(nullable = false)
    private int damaged;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Stock() {
        // JPA
    }

    public Stock(UUID id, UUID organizationId, UUID variantId) {
        this.id = id;
        this.organizationId = organizationId;
        this.variantId = variantId;
        this.onHand = 0;
        this.reserved = 0;
        this.damaged = 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public int getOnHand() {
        return onHand;
    }

    public int getReserved() {
        return reserved;
    }

    public int getDamaged() {
        return damaged;
    }

    void adjustReserved(int delta) {
        this.reserved += delta;
    }

    void adjustOnHand(int delta) {
        this.onHand += delta;
    }

    void adjustDamaged(int delta) {
        this.damaged += delta;
    }
}
