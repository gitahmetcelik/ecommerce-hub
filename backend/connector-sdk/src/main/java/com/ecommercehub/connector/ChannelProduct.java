package com.ecommercehub.connector;

/**
 * @param availableQuantity what the channel currently believes it can sell. Null when
 *                          the channel does not report stock on its catalog feed — plan
 *                          §11's nightly reconcile compares this against our own
 *                          computed availability, and a null must be skipped rather
 *                          than read as zero, which would fabricate a drift report for
 *                          every variant on such a channel.
 */
public record ChannelProduct(String channelProductId, String channelVariantId, String sku, String barcode,
                              String title, Integer availableQuantity) {

    public ChannelProduct(String channelProductId, String channelVariantId, String sku, String barcode, String title) {
        this(channelProductId, channelVariantId, sku, barcode, title, null);
    }
}
