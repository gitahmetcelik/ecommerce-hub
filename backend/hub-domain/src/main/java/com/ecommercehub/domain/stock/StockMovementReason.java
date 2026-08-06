package com.ecommercehub.domain.stock;

/**
 * Each reason touches exactly one counter (reserved, on_hand, or damaged) — the
 * ledger is unambiguous to replay without needing per-reason tribal knowledge of
 * which column it affects (needed later for Phase 4's ledger-vs-stock consistency check).
 */
public enum StockMovementReason {
    RESERVED_INCREASE,   // order item created — hold placed
    RESERVED_DECREASE,   // cancelled, expired, or consumed by shipment
    ON_HAND_INCREASE,    // intact return
    ON_HAND_DECREASE,    // shipped
    DAMAGED_INCREASE     // damaged return
}
