package com.ecommercehub.domain.returns;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Plan §7. One customer return, covering one or more items of a single order. */
@Entity
@Table(name = "return_request", schema = "hub")
public class ReturnRequest {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "sales_order_id", nullable = false, updatable = false)
    private UUID salesOrderId;

    @Column(name = "channel_return_id", updatable = false)
    private String channelReturnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReturnStatus status;

    private String reason;

    @Column(name = "approved_by_user_id")
    private UUID approvedByUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "reminder_at")
    private Instant reminderAt;

    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "timeout_at")
    private Instant timeoutAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    /**
     * Whether the goods came back sellable. Null until they are received, and null is
     * meaningfully different from false: "not inspected yet" is not "damaged".
     */
    @Column(name = "is_intact")
    private Boolean intact;

    /** How many times creating the return label has failed. Persisted: each retry is a fresh transaction. */
    @Column(name = "shipment_attempts", nullable = false)
    private int shipmentAttempts;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ReturnRequest() {
        // JPA
    }

    public ReturnRequest(UUID id, UUID organizationId, UUID salesOrderId, String channelReturnId, String reason) {
        this.id = id;
        this.organizationId = organizationId;
        this.salesOrderId = salesOrderId;
        this.channelReturnId = channelReturnId;
        this.reason = reason;
        this.status = ReturnStatus.REQUESTED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getSalesOrderId() {
        return salesOrderId;
    }

    public String getChannelReturnId() {
        return channelReturnId;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public UUID getApprovedByUserId() {
        return approvedByUserId;
    }

    public Instant getTimeoutAt() {
        return timeoutAt;
    }

    public Instant getRemindedAt() {
        return remindedAt;
    }

    public Boolean getIntact() {
        return intact;
    }

    void moveTo(ReturnStatus next) {
        this.status = next;
    }

    void startApprovalWindow(Instant reminderAt, Instant timeoutAt) {
        this.status = ReturnStatus.AWAITING_APPROVAL;
        this.reminderAt = reminderAt;
        this.timeoutAt = timeoutAt;
    }

    void markReminded(Instant at) {
        this.remindedAt = at;
    }

    void approve(UUID userId, Instant at) {
        this.status = ReturnStatus.ACCEPTED;
        this.approvedByUserId = userId;
        this.approvedAt = at;
    }

    void reject(UUID userId, Instant at, String reason) {
        this.status = ReturnStatus.REJECTED;
        this.approvedByUserId = userId;
        this.approvedAt = at;
        this.rejectionReason = reason;
    }

    void recordReceipt(boolean intact) {
        this.status = ReturnStatus.RETURN_RECEIVED;
        this.intact = intact;
    }

    public int getShipmentAttempts() {
        return shipmentAttempts;
    }

    int recordShipmentAttemptFailed() {
        return ++shipmentAttempts;
    }
}
