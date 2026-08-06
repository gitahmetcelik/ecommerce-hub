package com.ecommercehub.domain.intent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Plan §3 kanal_cagri_niyeti / §4.3: the persisted record of a side-effecting
 * connector call (KARGO_OLUSTUR, IADE_KARARI, PARA_IADESI, ...). target_reference is
 * always the id of the domain row the action belongs to (kargo.id, iade.id,
 * iade_odemesi.id) — never a sales_order or return_request id, so a second shipment
 * or a partial second refund is representable rather than permanently blocked (plan
 * §3, the v3→v4 fix this table's UNIQUE constraint exists to preserve).
 */
@Entity
@Table(name = "channel_call_intent", schema = "hub")
public class ChannelCallIntent {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "channel_connection_id", nullable = false, updatable = false)
    private UUID channelConnectionId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(name = "target_reference", nullable = false, updatable = false)
    private UUID targetReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_summary")
    private String requestSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntentStatus status;

    @Column(name = "channel_idempotency_key")
    private String channelIdempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_response")
    private String channelResponse;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ChannelCallIntent() {
        // JPA
    }

    public ChannelCallIntent(UUID id, UUID organizationId, UUID channelConnectionId, String type,
                              UUID targetReference, String requestSummary) {
        this.id = id;
        this.organizationId = organizationId;
        this.channelConnectionId = channelConnectionId;
        this.type = type;
        this.targetReference = targetReference;
        this.requestSummary = requestSummary;
        this.status = IntentStatus.PREPARED;
        // Plan §3: "kanal_idempotency_anahtari = niyet id'si" — the intent's own id
        // doubles as the idempotency key handed to the channel, when it supports one.
        this.channelIdempotencyKey = id.toString();
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

    public String getType() {
        return type;
    }

    public UUID getTargetReference() {
        return targetReference;
    }

    public String getRequestSummary() {
        return requestSummary;
    }

    public IntentStatus getStatus() {
        return status;
    }

    public String getChannelIdempotencyKey() {
        return channelIdempotencyKey;
    }

    public String getChannelResponse() {
        return channelResponse;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void markSent() {
        requireStatus(IntentStatus.PREPARED, "markSent");
        this.status = IntentStatus.SENT;
    }

    void recordResult(String channelResponse) {
        requireStatus(IntentStatus.SENT, "recordResult");
        this.channelResponse = channelResponse;
        this.status = IntentStatus.RESULT_RECEIVED;
    }

    void markAmbiguous() {
        requireStatus(IntentStatus.SENT, "markAmbiguous");
        this.status = IntentStatus.AMBIGUOUS;
    }

    private void requireStatus(IntentStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Cannot " + action + " intent " + id + ": expected status " + expected + " but was " + status);
        }
    }
}
