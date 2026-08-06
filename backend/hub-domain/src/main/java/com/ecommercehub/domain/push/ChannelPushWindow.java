package com.ecommercehub.domain.push;

import java.util.UUID;

/**
 * The payload of a {@code push-send} task. Plan §4.2 (v4) makes this task
 * <em>per window</em>, not per push row: one task collects everything pending for the
 * connection when it runs. A per-row task would defeat the coalescing entirely — 50
 * changes in a second would produce 50 tasks and 50 calls, which is the exact outcome
 * the coalescing table exists to prevent.
 */
public record ChannelPushWindow(UUID organizationId, UUID channelConnectionId, String windowStart) {
}
