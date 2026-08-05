package com.ecommercehub.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every order-related webhook (created, paid, cancelled, ...) carries the FULL
 * current order snapshot, not a delta — the same design real channels like Shopify
 * use. This is what sidesteps the "payment.succeeded arrives before order.created"
 * ordering problem (plan §2 Faz 2 gate) entirely: whichever event arrives first
 * creates the order at whatever state it describes; the second one, whenever it
 * shows up, upserts against an already-correct picture instead of a delta that
 * assumes an order already exists.
 */
public record OrderEventPayload(UUID organizationId, UUID channelConnectionId, String channelEventId,
                                 String channelOrderNumber, Instant channelEventAt, Long channelEventSequence,
                                 BigDecimal total, String currency, List<OrderEventItem> items, String traceId) {

    /**
     * channelProductId/channelVariantId/barcode feed catalog matching (plan §3/Faz 3)
     * — sku alone is not always what the channel uses as its own stable identifier.
     * A caller that only has a sku (no separate channel-side id) may pass sku for
     * channelVariantId/channelProductId too; barcode is optional.
     */
    public record OrderEventItem(String sku, String channelProductId, String channelVariantId, String barcode,
                                  int quantity, BigDecimal unitPrice, BigDecimal vatRate,
                                  OrderItemStatus targetStatus) {
    }
}
