package com.ecommercehub.connector;

/**
 * The three identifiers a channel item has, kept apart on purpose (Plan §3: sku,
 * barcode and the channel's own variant id are three concepts, never substitutes).
 *
 * <p>channelVariantId is the only one guaranteed present — hub.channel_product_mapping
 * declares it NOT NULL and unique per connection, so it is the correlation key for
 * bulk results. sku is ours and may be one we minted; barcode may be absent entirely.
 *
 * <p>Which of the three goes on the wire is the connector's decision. A SKU-keyed
 * marketplace sends sku(); a barcode-keyed one sends channelVariantId(). The domain
 * must not need to know which, because "which identifier does this channel use" is
 * exactly the difference the capability matrix exists to absorb.
 */
public record ChannelItemRef(String channelVariantId, String sku, String barcode) {
    public ChannelItemRef {
        if (channelVariantId == null || channelVariantId.isBlank()) {
            throw new IllegalArgumentException(
                    "channelVariantId is the correlation key and cannot be blank");
        }
    }
}
