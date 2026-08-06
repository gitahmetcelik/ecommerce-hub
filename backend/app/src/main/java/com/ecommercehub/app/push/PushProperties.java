package com.ecommercehub.app.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hub.push")
public class PushProperties {

    /** plan §3: "Gönderim görevi 5 sn'lik pencerede bekleyen satırları toplar". */
    private long windowMs = 5000;

    /** Upper bound on rows per batch call — plan §8 assumes ~1000 SKUs per call is fine. */
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
