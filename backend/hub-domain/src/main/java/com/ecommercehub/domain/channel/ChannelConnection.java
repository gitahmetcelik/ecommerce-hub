package com.ecommercehub.domain.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_connection", schema = "hub")
public class ChannelConnection {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "channel_type", nullable = false)
    private String channelType;

    @Column(name = "encrypted_credentials", nullable = false)
    private String encryptedCredentials;

    @Column(name = "key_version", nullable = false)
    private short keyVersion;

    @Column(nullable = false)
    private String status;

    @Column(name = "reconcile_interval_minutes", nullable = false)
    private int reconcileIntervalMinutes;

    @Column(name = "next_reconcile_at")
    private Instant nextReconcileAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ChannelConnection() {
        // JPA
    }

    public ChannelConnection(UUID id, UUID organizationId, String channelType,
                              String encryptedCredentials, short keyVersion) {
        this.id = id;
        this.organizationId = organizationId;
        this.channelType = channelType;
        this.encryptedCredentials = encryptedCredentials;
        this.keyVersion = keyVersion;
        this.status = "ACTIVE";
        this.reconcileIntervalMinutes = 5;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getChannelType() {
        return channelType;
    }

    public String getEncryptedCredentials() {
        return encryptedCredentials;
    }

    public short getKeyVersion() {
        return keyVersion;
    }

    public String getStatus() {
        return status;
    }
}
