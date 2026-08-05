package com.ecommercehub.domain.order;

/**
 * plan §6: "Geçersiz geçiş = hata değil → görev ERTELENDİ + üstel gecikmeli yeniden
 * deneme." Thrown to let the task engine's own retry/backoff (plan §3 dayanıklılık,
 * already built) BE the deferral mechanism, instead of a second bespoke queue.
 * Rolls the whole event back — safe because every already-applied item in the same
 * event is target-status idempotent and simply no-ops on retry.
 */
public class OrderItemTransitionDeferredException extends RuntimeException {
    public OrderItemTransitionDeferredException(String message) {
        super(message);
    }
}
