package com.ecommercehub.ingest;

import com.ecommercehub.domain.order.OrderItemStatus;

import java.util.Map;

/** Maps a webhook's eventType to the item status it targets, unless an item overrides it explicitly. */
final class EventTypeStatusMapping {

    private static final Map<String, OrderItemStatus> BY_EVENT_TYPE = Map.of(
            "order.created", OrderItemStatus.CREATED,
            "payment.succeeded", OrderItemStatus.PAID,
            "order.preparing", OrderItemStatus.PREPARING,
            "order.shipped", OrderItemStatus.SHIPPED,
            "order.delivered", OrderItemStatus.DELIVERED,
            "order.cancelled", OrderItemStatus.CANCELLED
    );

    private EventTypeStatusMapping() {
    }

    static OrderItemStatus defaultTargetFor(String eventType) {
        OrderItemStatus status = BY_EVENT_TYPE.get(eventType);
        if (status == null) {
            throw new IllegalArgumentException("Unknown webhook eventType: " + eventType);
        }
        return status;
    }
}
