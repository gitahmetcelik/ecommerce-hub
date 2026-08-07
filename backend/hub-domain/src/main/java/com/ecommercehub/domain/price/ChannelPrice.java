package com.ecommercehub.domain.price;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Plan §6.2 point 2: a per-channel price override. Its existence (and {@code active}
 * flag) is what {@link PriceService#effectivePriceFor} treats as "this channel does not
 * follow the list price" — deleting the row is how a channel reverts to it (Plan §6.4
 * gate: "channel price overrides the list price; deleting the channel price reverts to
 * it").
 */
@Entity
@Table(name = "channel_price", schema = "hub")
public class ChannelPrice {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "channel_connection_id", nullable = false, updatable = false)
    private UUID channelConnectionId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "discounted_price")
    private BigDecimal discountedPrice;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ChannelPrice() {
        // JPA
    }

    public ChannelPrice(UUID id, UUID organizationId, UUID channelConnectionId, UUID variantId,
                         BigDecimal price, BigDecimal discountedPrice) {
        this.id = id;
        this.organizationId = organizationId;
        this.channelConnectionId = channelConnectionId;
        this.variantId = variantId;
        this.price = price;
        this.discountedPrice = discountedPrice;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getChannelConnectionId() {
        return channelConnectionId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getDiscountedPrice() {
        return discountedPrice;
    }

    public boolean isActive() {
        return active;
    }

    void apply(BigDecimal price, BigDecimal discountedPrice) {
        this.price = price;
        this.discountedPrice = discountedPrice;
        this.active = true;
    }
}
