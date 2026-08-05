package com.ecommercehub.connector.ratelimit;

/**
 * plan §9: reserved shares of a channel connection's rate limit. Priority order is
 * declaration order — INTERACTIVE outranks OPERATIONAL outranks BACKGROUND, which is
 * what "unused capacity flows to higher classes, never the reverse" means in
 * {@link RateLimitBudget}.
 */
public enum BudgetClass {
    INTERACTIVE(0.50),
    OPERATIONAL(0.30),
    BACKGROUND(0.20);

    private final double share;

    BudgetClass(double share) {
        this.share = share;
    }

    public double share() {
        return share;
    }
}
