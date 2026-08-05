package com.ecommercehub.domain.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Append-only audit trail — never updated, never deleted. */
@Entity
@Table(name = "stock_movement", schema = "hub")
public class StockMovement {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private StockMovementReason reason;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() {
        // JPA
    }

    public StockMovement(UUID id, UUID organizationId, UUID variantId, int quantity,
                          StockMovementReason reason, UUID referenceId) {
        this.id = id;
        this.organizationId = organizationId;
        this.variantId = variantId;
        this.quantity = quantity;
        this.reason = reason;
        this.referenceId = referenceId;
    }

    public UUID getId() {
        return id;
    }

    public StockMovementReason getReason() {
        return reason;
    }

    public int getQuantity() {
        return quantity;
    }
}
