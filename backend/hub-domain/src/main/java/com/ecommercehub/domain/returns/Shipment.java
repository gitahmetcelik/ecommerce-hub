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
 * plan §3: a parcel, outbound or returning.
 *
 * <p>Like {@link ReturnPayment}, this row is committed before the intent that points
 * at it. plan §3 (v4) makes {@code shipment.id} the intent's target reference rather
 * than the order or the return, because one order can ship in parts and a lost parcel
 * can be re-sent — "one shipment per order" is the wrong granularity and would block
 * the second one forever.
 */
@Entity
@Table(name = "shipment", schema = "hub")
public class Shipment {

    public static final String DIRECTION_OUTBOUND = "OUTBOUND";
    public static final String DIRECTION_RETURN = "RETURN";

    /** We asked the channel to create the label. */
    public static final String SOURCE_CREATED_BY_US = "CREATED_BY_US";
    /** The channel produced the label on its own; we only recorded it (plan §7). */
    public static final String SOURCE_PROVIDED_BY_CHANNEL = "PROVIDED_BY_CHANNEL";

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_DELIVERED = "DELIVERED";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "sales_order_id")
    private UUID salesOrderId;

    @Column(name = "return_request_id")
    private UUID returnRequestId;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(nullable = false, updatable = false)
    private String direction;

    @Column(nullable = false, updatable = false)
    private String source;

    @Column(name = "channel_shipment_id")
    private String channelShipmentId;

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

    protected Shipment() {
        // JPA
    }

    public Shipment(UUID id, UUID organizationId, UUID salesOrderId, UUID returnRequestId,
                     String direction, String source) {
        this.id = id;
        this.organizationId = organizationId;
        this.salesOrderId = salesOrderId;
        this.returnRequestId = returnRequestId;
        this.direction = direction;
        this.source = source;
        this.status = STATUS_CREATED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReturnRequestId() {
        return returnRequestId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getSource() {
        return source;
    }

    public void recordChannelResult(String channelShipmentId, String trackingNumber) {
        this.channelShipmentId = channelShipmentId;
        this.trackingNumber = trackingNumber;
    }
}
