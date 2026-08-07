package com.ecommercehub.domain.push;

/**
 * PENDING and SENT are Plan §3's two states. SENDING is the in-flight marker that
 * keeps two overlapping send windows from picking the same row twice; it is not a
 * terminal state and every path out of it (success, failure, or a value that changed
 * underneath) goes through the generation CAS.
 *
 * <p>STUCK is Plan v5 §1.7 gate 3: a row whose {@code consecutive_failures} crossed the
 * threshold. It is excluded from both the claim query and PushWindowScheduler's
 * "does this connection have pending work" query — the row stays out of the retry
 * loop until an enqueue (a genuinely new value) puts it back to PENDING, at which
 * point the failure count (not reset by an enqueue) picks up where it left off.
 */
public final class ChannelPushStatus {

    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String STUCK = "STUCK";

    private ChannelPushStatus() {
    }
}
