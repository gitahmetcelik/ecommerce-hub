package com.ecommercehub.domain.push;

/**
 * Whether a channel actually supports a given push type at all, checked before a push
 * row for it is ever created.
 *
 * <p>Plan v5 §6.2 point 7: a channel with no {@code PRICE_PUSH} capability must never
 * accumulate a push row that nothing can send — {@link ChannelPushSender} only consumes
 * rows for the types it knows how to hand to a connector, so a row for an unsupported
 * type would sit PENDING forever and keep re-opening send windows for it.
 *
 * <p>hub-domain has no dependency on connector-sdk (where {@code Capability} actually
 * lives), so this takes plain strings — the implementation, which does see both, is the
 * only place that translates a push type into a capability.
 */
@FunctionalInterface
public interface ChannelPushCapabilityChecker {

    boolean supports(String channelType, String pushType);
}
