package com.ecommercehub.domain.stock;

import java.util.UUID;

/**
 * What one channel should be told this variant's sellable quantity is, after the
 * per-channel buffer and (below the low-stock threshold) the last-unit allocation
 * policy have been applied. {@code channelVariantId} comes from the mapping — the
 * channel's own identifier, never our sku — Plan §3 keeps sku, barcode and the channel's
 * variant id as three separate concepts.
 */
public record ChannelAvailability(UUID channelConnectionId, String channelVariantId, String sku, int quantity) {
}
