package com.ecommercehub.app.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Plan §4.4: retention on the engine's own tables (no built-in cleanup there — verified
 * by reading its migrations, plan §1.3) and on hub.raw_event's monthly partitions (§3, §12
 * Faz 7 gate). "Süre görev tipi bazında ayarlanır" (per-task-type retention) is honored
 * through {@link #motorTaskRetentionDaysByType}; anything not listed falls back to
 * {@link #motorTaskRetentionDaysDefault}.
 */
@ConfigurationProperties(prefix = "hub.retention")
public class RetentionProperties {

    /** Days a TAMAMLANDI (completed) engine task and its attempts/outbox rows are kept. */
    private int motorTaskRetentionDaysDefault = 30;

    private java.util.Map<String, Integer> motorTaskRetentionDaysByType = new java.util.HashMap<>();

    /** Days a hub.raw_event monthly partition is kept before being dropped. */
    private int rawEventRetentionDays = 90;

    /** How many months ahead raw_event partitions are pre-created. */
    private int rawEventPartitionsAheadMonths = 2;

    public int getMotorTaskRetentionDaysDefault() {
        return motorTaskRetentionDaysDefault;
    }

    public void setMotorTaskRetentionDaysDefault(int motorTaskRetentionDaysDefault) {
        this.motorTaskRetentionDaysDefault = motorTaskRetentionDaysDefault;
    }

    public java.util.Map<String, Integer> getMotorTaskRetentionDaysByType() {
        return motorTaskRetentionDaysByType;
    }

    public void setMotorTaskRetentionDaysByType(java.util.Map<String, Integer> motorTaskRetentionDaysByType) {
        this.motorTaskRetentionDaysByType = motorTaskRetentionDaysByType;
    }

    public int retentionDaysFor(String taskType) {
        return motorTaskRetentionDaysByType.getOrDefault(taskType, motorTaskRetentionDaysDefault);
    }

    public int getRawEventRetentionDays() {
        return rawEventRetentionDays;
    }

    public void setRawEventRetentionDays(int rawEventRetentionDays) {
        this.rawEventRetentionDays = rawEventRetentionDays;
    }

    public int getRawEventPartitionsAheadMonths() {
        return rawEventPartitionsAheadMonths;
    }

    public void setRawEventPartitionsAheadMonths(int rawEventPartitionsAheadMonths) {
        this.rawEventPartitionsAheadMonths = rawEventPartitionsAheadMonths;
    }
}
