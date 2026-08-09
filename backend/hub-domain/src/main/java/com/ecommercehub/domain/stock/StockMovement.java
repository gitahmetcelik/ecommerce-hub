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

    // Plan v5 Faz 7 (V1008): populated only for a manual correction — every other
    // reason (order, return, shipment) leaves all three null. adjustmentReason is kept
    // as a string, not the enum, so a value stays readable even if StockAdjustmentReason
    // ever loses a constant.
    @Column(name = "adjustment_reason", updatable = false)
    private String adjustmentReason;

    @Column(updatable = false)
    private String note;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() {
        // JPA
    }

    public StockMovement(UUID id, UUID organizationId, UUID variantId, int quantity,
                          StockMovementReason reason, UUID referenceId) {
        this(id, organizationId, variantId, quantity, reason, referenceId, null, null, null);
    }

    public StockMovement(UUID id, UUID organizationId, UUID variantId, int quantity, StockMovementReason reason,
                          UUID referenceId, StockAdjustmentReason adjustmentReason, String note, UUID actorUserId) {
        this.id = id;
        this.organizationId = organizationId;
        this.variantId = variantId;
        this.quantity = quantity;
        this.reason = reason;
        this.referenceId = referenceId;
        this.adjustmentReason = adjustmentReason == null ? null : adjustmentReason.name();
        this.note = note;
        this.actorUserId = actorUserId;
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
