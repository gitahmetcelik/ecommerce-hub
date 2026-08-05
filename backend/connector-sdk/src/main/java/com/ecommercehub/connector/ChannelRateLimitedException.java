package com.ecommercehub.connector;

/** Thrown by any PlatformConnector method when the channel itself responds 429. */
public class ChannelRateLimitedException extends RuntimeException {
    public ChannelRateLimitedException(String message) {
        super(message);
    }
}
