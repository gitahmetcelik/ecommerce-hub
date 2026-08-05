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
 * plan §3: sku, barcode, and channel_variant_id are three separate concepts, never
 * substituted for one another — this row is the durable link between "the channel's
 * idea of this item" and "our variant", however it was established.
 */
@Entity
@Table(name = "channel_product_mapping", schema = "hub")
public class ChannelProductMapping {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "channel_connection_id", nullable = false, updatable = false)
    private UUID channelConnectionId;

    @Column(name = "channel_product_id", nullable = false, updatable = false)
    private String channelProductId;

    @Column(name = "channel_variant_id", nullable = false, updatable = false)
    private String channelVariantId;

    @Column(name = "mapping_source", nullable = false, updatable = false)
    private String mappingSource;

    @Column(name = "matched_by_user_id", updatable = false)
    private UUID matchedByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ChannelProductMapping() {
        // JPA
    }

    public ChannelProductMapping(UUID id, UUID organizationId, UUID variantId, UUID channelConnectionId,
                                  String channelProductId, String channelVariantId,
                                  MappingSource mappingSource, UUID matchedByUserId) {
        this.id = id;
        this.organizationId = organizationId;
        this.variantId = variantId;
        this.channelConnectionId = channelConnectionId;
        this.channelProductId = channelProductId;
        this.channelVariantId = channelVariantId;
        this.mappingSource = mappingSource.name();
        this.matchedByUserId = matchedByUserId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getChannelVariantId() {
        return channelVariantId;
    }

    public String getMappingSource() {
        return mappingSource;
    }

    public enum MappingSource { AUTO_SKU, AUTO_BARCODE, MANUAL }
}
