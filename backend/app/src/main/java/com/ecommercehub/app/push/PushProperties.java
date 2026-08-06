package com.ecommercehub.app.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hub.push")
public class PushProperties {

    /** Plan §3: "the send task collects whatever is pending in a 5-second window". */
    private long windowMs = 5000;

    /** Upper bound on rows per batch call — Plan §8 assumes ~1000 SKUs per call is fine. */
    private int windowBatchLimit = 1000;

    public long getWindowMs() {
        return windowMs;
    }

    public void setWindowMs(long windowMs) {
        this.windowMs = windowMs;
    }

    public int getWindowBatchLimit() {
        return windowBatchLimit;
    }

    public void setWindowBatchLimit(int windowBatchLimit) {
        this.windowBatchLimit = windowBatchLimit;
    }
}
