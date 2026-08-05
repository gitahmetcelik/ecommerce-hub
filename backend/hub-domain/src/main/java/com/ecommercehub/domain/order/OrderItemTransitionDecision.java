package com.ecommercehub.domain.order;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * plan §6's decision table as a pure function — no side effects, no persistence,
 * just "given where an item is and where an event wants to send it, what happens."
 *
 * <pre>
 * | current   | incoming  | decision                                          |
 * | mainline  | mainline  | rank/sequence compare; not-newer => NO_OP         |
 * | mainline  | terminal  | allowed transition => APPLY, else => DEFER        |
 * | terminal  | mainline  | ESCALATE (channel un-cancelled — needs a human)   |
 * | terminal  | terminal (different) | ESCALATE                               |
 * | X         | X (same)  | NO_OP (target-status idempotency)                 |
 * </pre>
 */
public final class OrderItemTransitionDecision {

    public enum Action { APPLY, NO_OP, DEFER, ESCALATE }

    public record Decision(Action action, String reason) {
        static Decision of(Action action, String reason) {
            return new Decision(action, reason);
        }
    }

    // Terminal states only make sense from specific stages of the mainline — reachable
    // FROM these particular statuses, nothing else (plan doesn't spell out an exact
    // matrix; this is a defensible minimal reading, not load-bearing business logic).
    private static final Map<OrderItemStatus, Set<OrderItemStatus>> ALLOWED_TERMINAL_SOURCES = Map.of(
            OrderItemStatus.CANCELLED, Set.of(OrderItemStatus.CREATED, OrderItemStatus.AWAITING_PAYMENT, OrderItemStatus.PAID),
            OrderItemStatus.PAYMENT_TIMEOUT, Set.of(OrderItemStatus.CREATED, OrderItemStatus.AWAITING_PAYMENT),
            OrderItemStatus.IN_RETURN, Set.of(OrderItemStatus.SHIPPED, OrderItemStatus.DELIVERED)
    );

    private OrderItemTransitionDecision() {
    }

    public static Decision decide(OrderItemStatus current, Long currentSequence, Instant currentEventAt,
                                   OrderItemStatus target, Long targetSequence, Instant targetEventAt) {
        if (current == target) {
            return Decision.of(Action.NO_OP, "already at target status — target-status idempotency");
        }

        if (current.isTerminal()) {
            return Decision.of(Action.ESCALATE,
                    "current status " + current + " is terminal; incoming " + target + " needs a human, not an automatic transition");
        }

        if (target.isTerminal()) {
            Set<OrderItemStatus> allowedFrom = ALLOWED_TERMINAL_SOURCES.getOrDefault(target, Set.of());
            if (allowedFrom.contains(current)) {
                return Decision.of(Action.APPLY, "allowed mainline-to-terminal transition");
            }
            return Decision.of(Action.DEFER,
                    target + " is not reachable from " + current + " yet — retry with backoff instead of erroring");
        }

        // Both mainline from here on.
        boolean targetIsNewer;
        String basis;
        if (currentSequence != null && targetSequence != null) {
            targetIsNewer = targetSequence > currentSequence;
            basis = "channel event sequence";
        } else if (currentEventAt != null && targetEventAt != null && current.rank() == target.rank()) {
            // Unreachable in practice (ranks are unique per status) but kept explicit
            // rather than silently falling through — see class javadoc's ordering.
            targetIsNewer = targetEventAt.isAfter(currentEventAt);
            basis = "event timestamp (rank tie)";
        } else {
            targetIsNewer = target.rank() > current.rank();
            basis = "status rank";
        }

        if (targetIsNewer) {
            return Decision.of(Action.APPLY, "newer by " + basis);
        }
        return Decision.of(Action.NO_OP, "not newer by " + basis + " — late/duplicate event");
    }
}
