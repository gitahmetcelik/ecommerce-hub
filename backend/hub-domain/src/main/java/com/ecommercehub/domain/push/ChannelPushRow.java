package com.ecommercehub.domain.push;

import java.util.UUID;

/**
 * A single pending push, as read by the sender. {@code generation} is carried out of
 * the read and back into the CAS close (plan §3) — it is the whole mechanism by which
 * a value that changed mid-flight is not silently overwritten by a stale success.
 */
public record ChannelPushRow(UUID id, UUID organizationId, UUID channelConnectionId, UUID variantId,
                              String type, String targetValueJson, long generation) {
}
