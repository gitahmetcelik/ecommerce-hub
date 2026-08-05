package com.ecommercehub.connector;

/**
 * plan §8 KalemSonuc: "Per-item sonuç zorunlu — kısmi başarı ifade edilebilmeli." A
 * single result object for a bulk call would hide which of 1000 SKUs actually
 * failed. referenceId is a sku for stock/price bulk updates and a channelReturnId
 * for submitReturnDecision — the plan reuses one result shape across both, so this
 * does too, rather than one contextually-named type for stock/price and another for
 * return decisions.
 */
public record ItemResult(String referenceId, boolean success, String error) {
    public static ItemResult success(String referenceId) {
        return new ItemResult(referenceId, true, null);
    }

    public static ItemResult failure(String referenceId, String error) {
        return new ItemResult(referenceId, false, error);
    }
}
