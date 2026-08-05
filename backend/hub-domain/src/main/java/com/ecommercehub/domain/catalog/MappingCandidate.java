package com.ecommercehub.domain.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * plan §3: an item the channel reports that couldn't be resolved to exactly one
 * variant — either nothing matched (candidateVariantIdsJson empty/null) or more than
 * one did (ambiguous, needs a human to pick). Never silently dropped (plan §3, Faz 3
 * gate): every unmatched item lands here and in the operator queue.
 */
@Entity
@Table(name = "mapping_candidate", schema = "hub")
public class MappingCandidate {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "channel_connection_id", nullable = false, updatable = false)
    private UUID channelConnectionId;

    @Column(name = "channel_product_id", nullable = false, updatable = false)
    private String channelProductId;

    @Column(name = "channel_variant_id", nullable = false, updatable = false)
    private String channelVariantId;

    @Column(updatable = false)
    private String barcode;

    @Column(updatable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_variant_ids")
    private String candidateVariantIdsJson;

    @Column(nullable = false)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected MappingCandidate() {
        // JPA
    }

    public MappingCandidate(UUID id, UUID organizationId, UUID channelConnectionId, String channelProductId,
                             String channelVariantId, String barcode, String title, String candidateVariantIdsJson) {
        this.id = id;
        this.organizationId = organizationId;
        this.channelConnectionId = channelConnectionId;
        this.channelProductId = channelProductId;
        this.channelVariantId = channelVariantId;
        this.barcode = barcode;
        this.title = title;
        this.candidateVariantIdsJson = candidateVariantIdsJson;
        this.status = Status.PENDING.name();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getChannelConnectionId() {
        return channelConnectionId;
    }

    public String getChannelProductId() {
        return channelProductId;
    }

    public String getChannelVariantId() {
        return channelVariantId;
    }

    public String getStatus() {
        return status;
    }

    public void markResolved() {
        this.status = Status.RESOLVED.name();
    }

    public void markIgnored() {
        this.status = Status.IGNORED.name();
    }

    public enum Status { PENDING, RESOLVED, IGNORED }
}
