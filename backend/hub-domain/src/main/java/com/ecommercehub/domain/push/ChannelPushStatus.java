package com.ecommercehub.domain.push;

/**
 * PENDING and SENT are Plan §3's two states. SENDING is the in-flight marker that
 * keeps two overlapping send windows from picking the same row twice; it is not a
 * terminal state and every path out of it (success, failure, or a value that changed
 * underneath) goes through the generation CAS.
 */
public final class ChannelPushStatus {

    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";

    private ChannelPushStatus() {
    }
}
