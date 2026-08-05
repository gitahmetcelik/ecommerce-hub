package com.ecommercehub.connector;

import java.util.Map;

/**
 * plan §3 raw_event: HMAC verification must happen against the exact bytes the
 * channel signed, before any JSON parsing touches them — bodyBytes is therefore the
 * untouched wire payload, not a re-serialized copy.
 */
public record RawRequest(byte[] bodyBytes, Map<String, String> headers) {
}
