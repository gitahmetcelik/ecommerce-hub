package com.ecommercehub.domain.push;

import java.util.UUID;

/**
 * The payload of a {@code push-send} task. Plan §4.2 (v4) makes this task
 * <em>per window</em>, not per push row: one task collects everything pending for the
 * connection when it runs. A per-row task would defeat the coalescing entirely — 50
 * changes in a second would produce 50 tasks and 50 calls, which is the exact outcome
 * the coalescing table exists to prevent.
 *
 * <p>Plan v5 §6.2 point 6: {@code type} is part of the window, not just the row —
 * without it, a stock window and a price window pending on the same connection at the
 * same tick would collide on one task key and one of the two would silently never run
 * (v4 §4.2's "same key = a task that never fires" failure mode).
 */
public record ChannelPushWindow(UUID organizationId, UUID channelConnectionId, String type, String windowStart) {
}
