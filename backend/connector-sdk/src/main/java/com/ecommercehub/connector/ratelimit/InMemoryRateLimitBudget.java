package com.ecommercehub.connector.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single-JVM {@link RateLimitBudget}. Correct for exactly one process holding the
 * tokens — which is why production (see the {@code app} module's DB-backed
 * implementation, Plan v5 Faz 5 §5.3) does not use this class: two worker instances
 * each holding their own {@code InMemoryRateLimitBudget} for the same channel
 * connection would each get the full share, silently doubling the effective budget.
 *
 * <p>Still the right tool where a test needs <em>a</em> budget object with no database
 * behind it — connector-level tests that exercise a real 429 response, for instance —
 * since the algorithm itself (50/30/20 split, idle capacity flowing to higher-priority
 * classes, BACKGROUND's self-backoff) is unchanged from before Faz 5 and does not need
 * a database to verify.
 */
public class InMemoryRateLimitBudget implements RateLimitBudget {

    private final Map<BudgetClass, Integer> capacityPerPeriod = new EnumMap<>(BudgetClass.class);
    private final Map<BudgetClass, AtomicInteger> tokens = new EnumMap<>(BudgetClass.class);
    private volatile Instant backgroundBackoffUntil = Instant.EPOCH;

    public InMemoryRateLimitBudget(int totalCapacityPerPeriod) {
        for (BudgetClass budgetClass : BudgetClass.values()) {
            int capacity = (int) Math.floor(totalCapacityPerPeriod * budgetClass.share());
            capacityPerPeriod.put(budgetClass, capacity);
            tokens.put(budgetClass, new AtomicInteger(capacity));
        }
    }

    @Override
    public synchronized void refill() {
        capacityPerPeriod.forEach((budgetClass, capacity) -> tokens.get(budgetClass).set(capacity));
    }

    @Override
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

    @Override
    public void reportRateLimited(BudgetClass requested, Duration backoff) {
        if (requested == BudgetClass.BACKGROUND) {
            backgroundBackoffUntil = Instant.now().plus(backoff);
        }
    }

    @Override
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
