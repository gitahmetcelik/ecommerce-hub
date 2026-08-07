package com.ecommercehub.connector;

/**
 * Plan §8 KalemSonuc: "per-item results are mandatory, so partial success can be expressed" A
 * single result object for a bulk call would hide which of 1000 SKUs actually
 * failed. referenceId is always the {@link ChannelItemRef#channelVariantId()} for
 * stock/price bulk updates — never sku, even on a SKU-keyed channel whose wire format
 * happens to echo sku back; the connector translates — and a channelReturnId for
 * submitReturnDecision — the plan reuses one result shape across both, so this does
 * too, rather than one contextually-named type for stock/price and another for return
 * decisions.
 */
public record ItemResult(String referenceId, boolean success, String error) {
    public static ItemResult success(String referenceId) {
        return new ItemResult(referenceId, true, null);
    }

    public static ItemResult failure(String referenceId, String error) {
        return new ItemResult(referenceId, false, error);
    }
}
