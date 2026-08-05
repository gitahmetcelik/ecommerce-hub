package com.ecommercehub.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * derived_status is denormalized from order_item statuses (plan §3) — never written
 * directly by anything except {@link OrderProcessingService}'s recomputation step,
 * which runs inside the same SELECT ... FOR UPDATE transaction as the item change
 * that triggered it.
 */
@Entity
@Table(name = "sales_order", schema = "hub")
public class SalesOrder {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "channel_connection_id", nullable = false, updatable = false)
    private UUID channelConnectionId;

    @Column(name = "channel_order_number", nullable = false, updatable = false)
    private String channelOrderNumber;

    @Column(name = "channel_event_at", nullable = false)
    private Instant channelEventAt;

    @Column(name = "channel_event_sequence")
    private Long channelEventSequence;

    @Column(name = "derived_status", nullable = false)
    private String derivedStatus;

    @Column(nullable = false)
    private BigDecimal total;

    // DB column is CHAR(3) (plan §3's "para kuralı"). columnDefinition alone changes
    // the DDL Hibernate would generate but NOT what it validates against — schema
    // validation compares JDBC type codes, so @JdbcTypeCode(CHAR) is required too,
    // or SessionFactory bootstrap fails outright against the real bpchar column.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Instant updatedAt;

    @Version
    private Long version;

    protected SalesOrder() {
        // JPA
    }

    public SalesOrder(UUID id, UUID organizationId, UUID channelConnectionId, String channelOrderNumber,
                       Instant channelEventAt, Long channelEventSequence, BigDecimal total, String currency) {
        this.id = id;
        this.organizationId = organizationId;
        this.channelConnectionId = channelConnectionId;
        this.channelOrderNumber = channelOrderNumber;
        this.channelEventAt = channelEventAt;
        this.channelEventSequence = channelEventSequence;
        this.derivedStatus = OrderItemStatus.CREATED.name();
        this.total = total;
        this.currency = currency;
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

    public String getChannelOrderNumber() {
        return channelOrderNumber;
    }

    public Instant getChannelEventAt() {
        return channelEventAt;
    }

    public Long getChannelEventSequence() {
        return channelEventSequence;
    }

    public String getDerivedStatus() {
        return derivedStatus;
    }

    public void setDerivedStatus(String derivedStatus) {
        this.derivedStatus = derivedStatus;
    }

    public void observeEvent(Instant eventAt, Long eventSequence) {
        if (eventAt.isAfter(this.channelEventAt)) {
            this.channelEventAt = eventAt;
        }
        if (eventSequence != null && (this.channelEventSequence == null || eventSequence > this.channelEventSequence)) {
            this.channelEventSequence = eventSequence;
        }
    }
}
