package com.ecommercehub.domain.returns;

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
 * One returned line. The intact/damaged split is recorded per item because one parcel
 * routinely comes back with a sellable unit and a broken one, and a single flag on the
 * return would force both into whichever answer was wrong for the other.
 */
@Entity
@Table(name = "return_item", schema = "hub")
public class ReturnItem {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "return_request_id", nullable = false, updatable = false)
    private UUID returnRequestId;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private UUID orderItemId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "intact_quantity")
    private Integer intactQuantity;

    @Column(name = "damaged_quantity")
    private Integer damagedQuantity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ReturnItem() {
        // JPA
    }

    public ReturnItem(UUID id, UUID organizationId, UUID returnRequestId, UUID orderItemId, int quantity) {
        this.id = id;
        this.organizationId = organizationId;
        this.returnRequestId = returnRequestId;
        this.orderItemId = orderItemId;
        this.quantity = quantity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderItemId() {
        return orderItemId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Integer getIntactQuantity() {
        return intactQuantity;
    }

    public Integer getDamagedQuantity() {
        return damagedQuantity;
    }

    /**
     * @throws IllegalArgumentException when the two do not add up to the returned
     *         quantity — units that are neither sellable nor damaged have simply gone
     *         missing from the count, and silently accepting that loses stock.
     */
    void recordDisposition(int intact, int damaged) {
        if (intact < 0 || damaged < 0 || intact + damaged != quantity) {
            throw new IllegalArgumentException("Disposition " + intact + " intact + " + damaged
                    + " damaged does not account for the " + quantity + " unit(s) returned");
        }
        this.intactQuantity = intact;
        this.damagedQuantity = damaged;
    }
}
