package com.ecommercehub.app.backfill;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hub.backfill")
public class BackfillProperties {

    private int pageSize = 50;

    /** How often every channel's RateLimitBudget resets to its full share (Plan §9: "per period"). */
    private long refillPeriodMs = 60_000;

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public long getRefillPeriodMs() {
        return refillPeriodMs;
    }

    public void setRefillPeriodMs(long refillPeriodMs) {
        this.refillPeriodMs = refillPeriodMs;
    }
}
