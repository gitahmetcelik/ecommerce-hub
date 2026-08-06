package com.ecommercehub.domain.returns;

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
 * Plan §3 (v4): one refund payment. This row — not the return — is what a refund
 * intent points at, because a partial return produces more than one payment and
 * "one intent per return" would make the second one permanently impossible.
 *
 * <p>It is also committed <em>before</em> its intent exists, so the intent's
 * UNIQUE(organization_id, type, target_reference) lands on a row that is already real.
 */
@Entity
@Table(name = "return_payment", schema = "hub")
public class ReturnPayment {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";
    /** The channel is the merchant of record here: we observe the refund, we do not make it. */
    public static final String STATUS_PAID_BY_CHANNEL = "PAID_BY_CHANNEL";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "return_request_id", nullable = false, updatable = false)
    private UUID returnRequestId;

    @Column(nullable = false)
    private BigDecimal amount;

    // CHAR(3) needs the explicit JDBC type: columnDefinition changes the DDL, but
    // Hibernate's schema *validation* still expects the default VARCHAR mapping.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String status;

    @Column(name = "approved_by_user_id")
    private UUID approvedByUserId;

    @Column(name = "channel_refund_id")
    private String channelRefundId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ReturnPayment() {
        // JPA
    }

    public ReturnPayment(UUID id, UUID organizationId, UUID returnRequestId, BigDecimal amount, String currency,
                          UUID approvedByUserId) {
        this.id = id;
        this.organizationId = organizationId;
        this.returnRequestId = returnRequestId;
        this.amount = amount;
        this.currency = currency;
        this.approvedByUserId = approvedByUserId;
        this.status = STATUS_PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReturnRequestId() {
        return returnRequestId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getChannelRefundId() {
        return channelRefundId;
    }

    public void markPaid(String channelRefundId, Instant at) {
        this.status = STATUS_PAID;
        this.channelRefundId = channelRefundId;
        this.paidAt = at;
    }

    public void markPaidByChannel(Instant at) {
        this.status = STATUS_PAID_BY_CHANNEL;
        this.paidAt = at;
    }
}
