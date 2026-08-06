package com.ecommercehub.connector;

/**
 * Plan §8 (durumSorgula): resolves an AMBIGUOUS intent by asking the channel directly.
 * resultJson is opaque (raw response text) on purpose — the caller (ChannelCallIntentService)
 * only needs to know whether an outcome was found, not to understand its shape.
 */
public record CallStatus(boolean resolved, String resultJson) {
    public static CallStatus resolved(String resultJson) {
        return new CallStatus(true, resultJson);
    }

    public static CallStatus unresolved() {
        return new CallStatus(false, null);
    }
}
