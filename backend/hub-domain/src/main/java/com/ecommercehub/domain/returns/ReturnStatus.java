package com.ecommercehub.domain.returns;

/**
 * plan §7's return state machine.
 *
 * <pre>
 * REQUESTED → AWAITING_APPROVAL → (approve) ACCEPTED → RETURN_SHIPMENT_CREATED
 *                   │                                          │
 *                   ├─(reject) REJECTED                        ▼
 *                   └─(48h)   TIMED_OUT → escalation    RETURN_RECEIVED
 *                                                              │
 *                                                      REFUND_CALCULATED
 *                                                              │
 *                                                         REFUNDED
 * </pre>
 *
 * <p>TIMED_OUT is not a rejection. plan §0 is explicit that an automatic rejection is
 * unacceptable — it is a customer-visible decision that nobody actually made — so the
 * 48-hour deadline escalates to a human and the return stays open.
 */
public enum ReturnStatus {

    REQUESTED,
    AWAITING_APPROVAL,
    ACCEPTED,
    REJECTED,

    /** Waited past the deadline without a human decision. Still open, now escalated. */
    TIMED_OUT,

    /**
     * The return label exists. Whether we created it or merely observed the channel
     * creating it depends on the SHIPMENT_CREATE capability — the state is the same
     * either way, which is what keeps channel differences out of this machine.
     */
    RETURN_SHIPMENT_CREATED,

    RETURN_RECEIVED,
    REFUND_CALCULATED,

    /** We paid it (REFUND_BY_US), or we observed the channel paying it. */
    REFUNDED;

    public boolean isTerminal() {
        return this == REJECTED || this == REFUNDED;
    }

    public boolean isAwaitingDecision() {
        return this == AWAITING_APPROVAL || this == TIMED_OUT;
    }
}
