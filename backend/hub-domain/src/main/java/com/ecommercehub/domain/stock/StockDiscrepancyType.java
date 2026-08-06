package com.ecommercehub.domain.stock;

/**
 * Plan §11. Both kinds are <em>reported and never auto-corrected</em> (Plan §0:
 * "stock drift is reported, never corrected automatically") — a system that silently rewrites
 * stock to match whatever a channel last said has no way to tell a real drift from a
 * channel bug, and the correction is exactly the action a human would want to see
 * before it happens.
 */
public final class StockDiscrepancyType {

    /** Our computed availability differs from what the channel reports. */
    public static final String CHANNEL_DRIFT = "CHANNEL_DRIFT";

    /**
     * The stock row disagrees with a replay of its own stock_movement ledger. Nothing
     * external is involved — a channel reconcile cannot detect this, because both sides
     * of that comparison are downstream of the same corrupted counter.
     */
    public static final String INTERNAL_INCONSISTENCY = "INTERNAL_INCONSISTENCY";

    private StockDiscrepancyType() {
    }
}
