package com.ecommercehub.domain.stock;

/**
 * Plan v5 §U5 / §7.5: two concurrent manual corrections must not silently overwrite
 * each other. The submitted correction carries the on-hand count the operator's screen
 * showed when they started; if the row has moved since, this is thrown instead of
 * quietly applying the new value's delta on top of a number the operator never saw.
 */
public class StockAdjustmentConflictException extends RuntimeException {

    private final int actualOnHand;

    public StockAdjustmentConflictException(int actualOnHand) {
        super("Stock has changed since this screen was loaded — it is now " + actualOnHand);
        this.actualOnHand = actualOnHand;
    }

    public int getActualOnHand() {
        return actualOnHand;
    }
}
