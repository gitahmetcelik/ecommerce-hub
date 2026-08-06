package com.ecommercehub.connector;

import java.util.UUID;

/**
 * The connector-facing view of a channel_call_intent row (Plan §3/§4.3/§8) — enough
 * to make an idempotent call and later ask "what happened to this", without coupling
 * the connector interface to the JPA entity that owns its persistence lifecycle.
 */
public record CallIntentRef(UUID intentId, String channelIdempotencyKey) {
}
