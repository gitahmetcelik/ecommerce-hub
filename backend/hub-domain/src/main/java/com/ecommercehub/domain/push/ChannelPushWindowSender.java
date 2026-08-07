package com.ecommercehub.domain.push;

import java.util.UUID;

/**
 * The seam between the {@code push-send} task handler and the code that actually
 * talks to a channel.
 *
 * <p>The implementation needs the connector registry and the credential store, which
 * live in modules above task-handlers. Declaring the capability here — where both
 * sides can see it — lets the handler stay in task-handlers with the others instead
 * of migrating into the application layer just to reach its collaborator.
 */
@FunctionalInterface
public interface ChannelPushWindowSender {

    /** @return how many push rows the channel confirmed in this window */
    int sendWindow(UUID organizationId, UUID channelConnectionId, String type);

    /** Convenience for the stock-only call sites that predate Plan v5 Faz 6's multi-type windows. */
    default int sendWindow(UUID organizationId, UUID channelConnectionId) {
        return sendWindow(organizationId, channelConnectionId, ChannelPushService.TYPE_STOCK);
    }
}
