package com.ecommercehub.domain.stock;

import java.util.UUID;

/**
 * What one channel should be told this variant's sellable quantity is, after the
 * per-channel buffer and (below the low-stock threshold) the last-unit allocation
 * policy have been applied. {@code channelVariantId} comes from the mapping — the
 * channel's own identifier, never our sku, per plan §3's "sku, barkod ve
 * kanal_varyant_id üç ayrı kavramdır" rule.
 */
public record ChannelAvailability(UUID channelConnectionId, String channelVariantId, String sku, int quantity) {
}
