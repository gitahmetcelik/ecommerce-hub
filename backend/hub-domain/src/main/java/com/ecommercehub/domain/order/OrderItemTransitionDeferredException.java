package com.ecommercehub.domain.order;

/**
 * Plan §6: an invalid transition is not an error — the task is deferred and retried with
 * exponential backoff. Thrown to let the task engine's own retry (Plan §3 resilience,
 * already built) BE the deferral mechanism, instead of a second bespoke queue.
 * Rolls the whole event back — safe because every already-applied item in the same
 * event is target-status idempotent and simply no-ops on retry.
 */
public class OrderItemTransitionDeferredException extends RuntimeException {
    public OrderItemTransitionDeferredException(String message) {
        super(message);
    }
}
