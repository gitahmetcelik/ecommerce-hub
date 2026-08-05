package com.ecommercehub.ingest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * MOCK's webhook shape — a full order snapshot per event (see OrderEventPayload's
 * javadoc for why), not a delta. A real channel's payload would need its own parser
 * that produces the same {@link com.ecommercehub.domain.order.OrderEventPayload}.
 */
public record WebhookEnvelope(String eventId, String eventType, Instant eventAt, Long sequence, OrderPayload order) {

    public record OrderPayload(String channelOrderNumber, BigDecimal total, String currency, List<ItemPayload> items) {
    }

    public record ItemPayload(String sku, String channelProductId, String channelVariantId, String barcode,
                               int quantity, BigDecimal unitPrice, BigDecimal vatRate, String targetStatus) {
    }
}
