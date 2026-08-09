package com.ecommercehub.domain.stock;

/**
 * Plan v5 §U5: why a human corrected the count, distinct from {@link StockMovementReason}
 * (which counter/direction the ledger replay needs). Enum + free note — enum alone loses
 * the real reason, free text alone cannot be reported on (Plan §U5).
 */
public enum StockAdjustmentReason {
    COUNT_DISCREPANCY,
    DAMAGE,
    LOSS,
    WAREHOUSE_RECEIPT,
    OTHER
}
