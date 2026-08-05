package com.ecommercehub.connector.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * plan §9: a single channel connection's rate-limit budget, split 50/30/20 across
 * {@link BudgetClass#INTERACTIVE}/{@link BudgetClass#OPERATIONAL}/{@link BudgetClass#BACKGROUND}.
 * A shared, unpartitioned token bucket lets a nightly full reconcile (BACKGROUND) starve
 * the interactive stock push that's the actual anti-oversell mechanism — this exists so
 * that can't happen: each class gets a guaranteed floor, and only <em>idle</em> capacity
 * from lower-priority classes ever flows up to higher ones, never the reverse.
 */
public class RateLimitBudget {

    private final Map<BudgetClass, Integer> capacityPerPeriod = new EnumMap<>(BudgetClass.class);
    private final Map<BudgetClass, AtomicInteger> tokens = new EnumMap<>(BudgetClass.class);
    private volatile Instant backgroundBackoffUntil = Instant.EPOCH;

    public RateLimitBudget(int totalCapacityPerPeriod) {
        for (BudgetClass budgetClass : BudgetClass.values()) {
            int capacity = (int) Math.floor(totalCapacityPerPeriod * budgetClass.share());
            capacityPerPeriod.put(budgetClass, capacity);
            tokens.put(budgetClass, new AtomicInteger(capacity));
        }
    }

    /** Resets every class back to its full share. Call once per rate-limit period (e.g. per minute). */
    public synchronized void refill() {
        capacityPerPeriod.forEach((budgetClass, capacity) -> tokens.get(budgetClass).set(capacity));
    }

    /**
     * Tries the requested class's own tokens first, then falls back to unused tokens
     * from strictly lower-priority classes (enum declaration order = priority order).
     * BACKGROUND additionally refuses to acquire at all while backed off.
     */
    public synchronized boolean tryAcquire(BudgetClass requested) {
        if (requested == BudgetClass.BACKGROUND && Instant.now().isBefore(backgroundBackoffUntil)) {
            return false;
        }
        if (tokens.get(requested).get() > 0) {
            tokens.get(requested).decrementAndGet();
            return true;
        }
        for (BudgetClass lowerPriority : lowerPriorityThan(requested)) {
            AtomicInteger lowerTokens = tokens.get(lowerPriority);
            if (lowerTokens.get() > 0) {
                lowerTokens.decrementAndGet();
                return true;
            }
        }
        return false;
    }

    /**
     * Only BACKGROUND self-throttles on a 429 (plan §9: "ARKAPLAN sınıfı 429 aldığında
     * kendini geri çeker, üst sınıfları etkilemez") — INTERACTIVE/OPERATIONAL tokens are
     * never touched by this, by design.
     */
    public void reportRateLimited(BudgetClass requested, Duration backoff) {
        if (requested == BudgetClass.BACKGROUND) {
            backgroundBackoffUntil = Instant.now().plus(backoff);
        }
    }

    public int remaining(BudgetClass budgetClass) {
        return tokens.get(budgetClass).get();
    }

    private static List<BudgetClass> lowerPriorityThan(BudgetClass budgetClass) {
        BudgetClass[] values = BudgetClass.values();
        List<BudgetClass> lower = new ArrayList<>();
        for (int i = budgetClass.ordinal() + 1; i < values.length; i++) {
            lower.add(values[i]);
        }
        return lower;
    }
}
