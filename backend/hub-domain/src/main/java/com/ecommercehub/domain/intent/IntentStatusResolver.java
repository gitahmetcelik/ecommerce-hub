package com.ecommercehub.domain.intent;

import java.util.Optional;

/**
 * The "durumSorgula" side of a connector (Plan §8's PlatformConnector.durumSorgula).
 * The actual connector contract doesn't exist yet (Phase 1) — this is the seam it will
 * plug into. Returns the channel's response (as JSON text) if the call's outcome
 * could be determined, empty if the channel itself can't say either.
 */
@FunctionalInterface
public interface IntentStatusResolver {
    Optional<String> queryStatus(ChannelCallIntent intent);
}
