package com.ecommercehub.domain.order;

/**
 * plan §6: the mainline chain has a total order (rank), used to compare "how far
 * along" two states are without caring which arrived first. Terminal states are
 * deliberately unranked — comparing IPTAL to KARGOLANDI is undefined, not "IPTAL is
 * behind", and OrderItemTransitionDecision must never pretend otherwise.
 */
public enum OrderItemStatus {
    CREATED(10),
    AWAITING_PAYMENT(20),
    PAID(30),
    PREPARING(40),
    SHIPPED(50),
    DELIVERED(60),

    CANCELLED(null),
    PAYMENT_TIMEOUT(null),
    IN_RETURN(null);

    private final Integer rank;

    OrderItemStatus(Integer rank) {
        this.rank = rank;
    }

    public boolean isMainline() {
        return rank != null;
    }

    public boolean isTerminal() {
        return rank == null;
    }

    public int rank() {
        if (rank == null) {
            throw new IllegalStateException(name() + " is a terminal status and has no rank");
        }
        return rank;
    }
}
