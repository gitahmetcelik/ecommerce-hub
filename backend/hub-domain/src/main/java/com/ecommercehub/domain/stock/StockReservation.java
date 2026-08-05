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
 * plan §3 rezervasyon semantiği: created with expires_at = now + 24h; payment
 * confirmation clears expires_at (stops the clock, plan's "son_gecerlilik kaldırılır");
 * a still-ticking reservation past its expiry is released by
 * {@link StockReservationExpiryService}, which also times out the order item.
 */
@Entity
@Table(name = "stock_reservation", schema = "hub")
public class StockReservation {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private UUID orderItemId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected StockReservation() {
        // JPA
    }

    public StockReservation(UUID id, UUID organizationId, UUID orderItemId, UUID variantId, int quantity, Instant expiresAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.orderItemId = orderItemId;
        this.variantId = variantId;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderItemId() {
        return orderItemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** plan §3: payment confirmation "removes" the expiry — the row stays, just stops ticking. */
    public void clearExpiry() {
        this.expiresAt = null;
    }
}
