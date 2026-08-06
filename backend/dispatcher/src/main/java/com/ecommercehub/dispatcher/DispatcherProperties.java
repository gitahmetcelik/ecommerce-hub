package com.ecommercehub.dispatcher;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Capacity equation (Plan §2.3): maxThroughput = organizationCount * perOrgPerCycle / cyclePeriod.
 * If that number falls short of the §5 target load, perOrgPerCycle/cyclePeriod are tuned here —
 * the dispatcher runs on a single ShedLock-guarded instance, so this equation is the real ceiling.
 */
@ConfigurationProperties(prefix = "hub.dispatcher")
public class DispatcherProperties {

    /** Max PENDING rows picked per organization in a single dispatch cycle. */
    private int perOrgPerCycle = 5;

    /** Max PENDING rows picked across all organizations in a single dispatch cycle. */
    private int totalPerCycle = 50;

    /** Max PROCESSING rows allowed at once for a single channel connection. */
    private int channelConcurrencyLimit = 5;

    /** A PROCESSING row older than (timeout * this multiplier) is escalated to the operator queue. */
    private int orphanTimeoutMultiplier = 3;

    /** How long a work_batch row may sit in PROCESSING before it's considered stuck. */
    private int processingTimeoutSeconds = 120;

    public int getPerOrgPerCycle() {
        return perOrgPerCycle;
    }

    public void setPerOrgPerCycle(int perOrgPerCycle) {
        this.perOrgPerCycle = perOrgPerCycle;
    }

    public int getTotalPerCycle() {
        return totalPerCycle;
    }

    public void setTotalPerCycle(int totalPerCycle) {
        this.totalPerCycle = totalPerCycle;
    }

    public int getChannelConcurrencyLimit() {
        return channelConcurrencyLimit;
    }

    public void setChannelConcurrencyLimit(int channelConcurrencyLimit) {
        this.channelConcurrencyLimit = channelConcurrencyLimit;
    }

    public int getOrphanTimeoutMultiplier() {
        return orphanTimeoutMultiplier;
    }

    public void setOrphanTimeoutMultiplier(int orphanTimeoutMultiplier) {
        this.orphanTimeoutMultiplier = orphanTimeoutMultiplier;
    }

    public int getProcessingTimeoutSeconds() {
        return processingTimeoutSeconds;
    }

    public void setProcessingTimeoutSeconds(int processingTimeoutSeconds) {
        this.processingTimeoutSeconds = processingTimeoutSeconds;
    }
}
