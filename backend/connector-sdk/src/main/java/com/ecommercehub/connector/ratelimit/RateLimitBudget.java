package com.ecommercehub.connector.ratelimit;

import java.time.Duration;

/**
 * Plan §9: a single channel connection's rate-limit budget, split 50/30/20 across
 * {@link BudgetClass#INTERACTIVE}/{@link BudgetClass#OPERATIONAL}/{@link BudgetClass#BACKGROUND}.
 * A shared, unpartitioned token bucket lets a nightly full reconcile (BACKGROUND) starve
 * the interactive stock push that's the actual anti-oversell mechanism — every
 * implementation exists so that can't happen: each class gets a guaranteed floor, and
 * only <em>idle</em> capacity from lower-priority classes ever flows up to higher ones,
 * never the reverse.
 *
 * <p>Plan v5 Faz 5 §5.3: this used to be a concrete class backing the budget with an
 * in-memory {@code ConcurrentHashMap} — correct for one JVM, silently wrong the moment
 * a second worker process exists, since each instance would get its own full share of
 * the same channel connection's budget. The interface is unchanged; only where the
 * tokens live moved, to {@code hub.channel_rate_budget} (see the {@code app} module's
 * DB-backed implementation).
 */
public interface RateLimitBudget {

    /**
     * Tries the requested class's own tokens first, then falls back to unused tokens
     * from strictly lower-priority classes (enum declaration order = priority order).
     * BACKGROUND additionally refuses to acquire at all while backed off.
     */
    boolean tryAcquire(BudgetClass requested);

    /**
     * Only BACKGROUND self-throttles on a 429 (Plan §9: the BACKGROUND class backs itself
     * off without affecting the classes above it) — INTERACTIVE and OPERATIONAL tokens are
     * never touched by this, by design.
     */
    void reportRateLimited(BudgetClass requested, Duration backoff);

    /** Resets every class back to its full share. Call once per rate-limit period (e.g. per minute). */
    void refill();

    int remaining(BudgetClass budgetClass);
}
